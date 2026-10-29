# Proxmox VE suite v2: cluster, node and guest resources

Status: **approved by the user 2026-10-25**; drafted 2026-10-21; revised 2026-10-24 with the v1 reuse decisions (§3.2,
§3.8) and topology corrections (§2, §3.4, §3.6); revised 2026-10-25 for v1 complete at `2e0237b`,
the NetScan spec (§3.3) and the v1-against-v2 licence comparison (§3.7); revised again 2026-10-25:
v2 is standalone, with its own token property `pve.token.credential` (§3.2, §3.5, §3.8);
revised again 2026-10-25 after spike S2: the cluster role is an active and a standby resource
(§3.1), the cluster health score (§3.2), and v1 is copied into this repository (§3.8). Nothing here is built yet; the plan is `docs/superpowers/plans/2026-10-25-proxmox-v2.md`. Research behind it:
`docs/research/2026-10-21-lm-vsphere-architecture.md`. v1 is the `Proxmox-LogicModule` repository
(19 modules, portal-verified on a two-node lab cluster).

## 1. Goal

Monitor Proxmox VE the way LogicMonitor monitors vSphere, so that the suite stays usable and
efficient at enterprise scale (Q6: 10 clusters of 16 nodes and 1,000 guests each, §3.9):

- **Every node is a resource with its own node-level data**, not an instance on one node's resource.
- **Cluster-level monitoring does not depend on one node being up.**
- **Guests can be resources**, created and kept current by a NetScan, organised in groups, with
  their node available as a property. This is optional.
- **API cost stays O(1) per poll for cluster-wide data**, as in v1 (v1 DESIGN §2).
- **No manual property-setting per node or per guest**, and no stale state when a node joins a
  cluster or a token moves.

## 2. Why v1 falls short

- **Everything hangs off the token holder.** One node's resource carries every node, guest and
  storage object as instances. With 1,000 guests that list is unreadable, and alert routing,
  maintenance windows and access control cannot target a guest or a node.
- **A single point of failure.** If the token holder goes down, all cluster monitoring stops.
- **Tokens and categories go stale.** `addCategory_Proxmox_VE` can add `ProxmoxVE` but never
  remove it. When a standalone host joined a cluster and gave up its token, it kept the category,
  so every module kept failing there, and its old instances kept old keys (v1 portal check,
  2026-10-21).

Topology is **not** a v1 shortfall. An earlier draft said the cluster is never drawn, citing the
first run of v1's spike 3; the re-run on 2026-10-22 drew the cluster vertex, through
`Proxmox_VE_Cluster`'s instance, with Cluster edges to every node (v1 DESIGN §7).

## 3. Design

### 3.1 Three resource roles, mirroring vSphere's three module sets

| Role | Category | vSphere analogue | Created by |
|---|---|---|---|
| **Cluster** | `ProxmoxVE_Cluster` | vCenter | NetScan, or manually |
| **Node** | `ProxmoxVE_Node` | ESXi host | NetScan, or manually |
| **Guest** | `ProxmoxVE_Guest` | VMwareVM | NetScan only, optional (`pve.netscan.guests`), **off by default** |

**Licensing decides the defaults** (§3.7): every resource costs one host unit (HU); instances
cost none. So a guest becomes a resource only when the user asks for it.

**Where the cluster role lives: an active resource and a standby** (user, 2026-10-25, replacing
Q8's first answer). The cluster role sits on an ordered pair of resources; the first one that is
up collects, the other withholds its data. Which pair depends on whether the cluster has an
address of its own:

| The cluster has | Active | Standby |
|---|---|---|
| a VIP (keepalived), a reverse proxy or load balancer, or a DNS name for the cluster | a **separate cluster resource**, hostname = that address | the node with the lowest node ID |
| round-robin DNS | the same, with the warning below | the same |
| **nothing** (the common case: Proxmox ships no cluster address, research `2026-10-25-proxmox-cluster-address.md`) | the node with the lowest node ID | the node with the next one |

**Why not a separate cluster resource always, as Q8 first chose.** A resource has to answer on its
own address or it is dead, and a dead resource hides the alerts we need most. In the lab, spike
S2 (2026-10-25) showed every step of that:

- `Labcluster`, created with the cluster name as its hostname, went dead: ping and DNS fail, and
  HostStatus's `idleInterval` climbed without stopping.
- A script DataSource collecting every minute did not revive it. LogicMonitor documents that
  "Data collected by script DataSources does not affect the value of the idleInterval
  datapoint" ([HostStatus](https://www.logicmonitor.com/support/hoststatus-datasource)).
- Neither did a `PingMulti-` DataSource with 0% loss to both nodes: replies from other addresses
  do not count.
- A loopback hostname (`127.0.0.2`) answers, but it made the resource a copy of the Collector's own
  host: LogicMonitor applied its Collector modules, and WMI applied the Hyper-V modules.
- And a dead resource silences itself: "When a host goes down, LogicMonitor does not generate a
  new alert (except HostStatus.)" and "All other existing alert notifications from the host are
  suppressed" (same page). Silencing HostStatus would leave every cluster alert suppressed.

So the cluster role goes on resources that really answer: the cluster's own address where one
exists, else a node, which is v1's model (the cluster as instances on a node resource) with its
weak point removed.

**How a standby knows to take over.** Every cluster module begins with one preamble check
(§3.8), using the properties the NetScan writes (NetScan spec §5.1):

- on the **active cluster resource** (a cluster address): collect;
- on an **active node**: collect if its own corosync is running and it is quorate **or the
  cluster has two nodes** (user, 2026-10-26: the survivor of a lost peer in a two-node cluster
  has no quorum, and must still report);
- on a **standby node** behind an active node: collect only if `/cluster/status` reports the
  active node offline, and the standby is quorate **or the cluster has two nodes** (user,
  2026-10-26). A two-node cluster loses quorum whenever either node is down, so requiring quorum
  there would leave no collector at all, as the two-node lab showed when S8 was planned. One
  collector either way: an active node without quorum has stopped. With three nodes or more, a
  standby cut off from the majority waits, since the majority side's active node still collects;
- on a **standby node** behind a cluster address: collect if that address fails to answer
  **either** a ping **or** a connection to its port 8006 (user, 2026-10-25, spec review). LogicMonitor judges the cluster
  resource alive by ping, so checking 8006 alone would leave a VIP whose ICMP is blocked dead,
  and its alerts suppressed, while the standby stayed quiet. The script's ping is Java's
  `InetAddress.isReachable`, a real ICMP echo only where the Collector has the privilege for one
  and a TCP echo otherwise [inferred], so the install guide requires the address to answer both
  ping and 8006.

- on **any node whose own corosync is not running** (its `/nodes/<self>/services`): never
  collect (user, 2026-10-26). S8 showed why: with corosync stopped on Node-A, its API kept
  answering with frozen membership (every node online) and a quorum flag that flipped between
  0 and 1 from poll to poll, so the active node kept collecting beside the standby. One more call,
  to the node itself; where the services cannot be read the check is skipped, not guessed;
- **a node's cluster modules read from that node's own API first,** then `pve.api.url` and
  `pve.api.hosts` (user, 2026-10-26): a standby that has taken over must report its own view, not
  the stale one at `pve.api.url`, which in S8 was the very node it stood in for.

Options weighed for the stale view (2026-10-26): asking the standby to confirm the active node is
online (trusts another node's view, itself stale if that node's corosync is down; one more call to
another node); a majority vote of every node (one call per node per check); letting the NetScan
move the role (up to an hour without cluster alerts, and the instances move). Checking the node's
own corosync was chosen: stale views come from exactly that, it needs no other node, and its one
gap is shared by every option -- a two-node cluster whose nodes cannot reach each other at all gets
two collectors, which a QDevice prevents.

Otherwise the module prints nothing and exits 0, so its datapoints read No Data. No Data does not
alert by default, so the pair never alerts twice. A node that is not quorate never counts itself
active, so a split cluster cannot have two active nodes. A three-node-or-larger cluster split so
that the standby is alone with the active node unreachable has the standby waiting (no quorum)
and the active node collecting (quorum): one collector. Collection never stops while any node
answers: the active resource's modules fail over through `pve.api.hosts`, and a dead resource's
scripts keep collecting (S2: `S2_KeepAlive` recorded through the dead state). Its alerts are what
the standby exists for.

- **Cost:** no host unit (the standby is a node resource that exists anyway); every cluster
  instance exists twice, active and standby (2,000 guest instances at 1,000 guests, free);
  the check, two calls a node makes to itself (`/cluster/status`, `/nodes/<self>/services`) on
  each cluster-module poll, about 180 an hour, and none on a cluster resource or behind a
  cluster address (§3.9's table; as built, not shared through `lm.cache`, 2026-10-26); during an
  outage the history sits on the standby.
- **Only two simultaneous failures,** the active and the standby, bring back the gap.
- **Round-robin DNS** works but is the weakest: LogicMonitor's ping may resolve the name to a
  node that is down, so the cluster resource flaps dead (and suppressed) while the standby's own
  lookup sees it up. The install guide supports it and recommends a VIP or a proxy.
- **Certificates:** behind a VIP or proxy, Proxmox's per-node certificates do not name the shared
  address; the user installs one that does (usual on a proxy) or sets `pve.api.insecure=true`.
- **Adding an address later:** the next NetScan run creates the cluster resource as active,
  turns the active node into the standby, and sets the old standby's role to `none`. New data
  lands on the cluster resource; the old active node keeps its history, now as the standby.
- **The hand-back** (user, 2026-10-26, after S8). S8 showed a standby's alerts do **not** clear
  when it goes quiet -- No Data never clears an alert -- so a standby that has been collecting
  collects **once more** after the resource ahead of it returns, and its healthy values clear what
  it raised. It remembers that it was collecting through the Collector's `lm.cache` (`cacheSet`,
  `cacheGet`, `cacheRemove`), which keeps a value between runs (S7, Collector debug console,
  2026-10-26); each cluster module keeps its own record, under its own name, for two hours,
  renewed on each poll it stands in. Where `lm.cache` cannot be loaded the hand-back is skipped.
  **The last poll waits for quorum** (review M4, 2026-10-26): a standby whose own view has no
  quorum, and is not one of two nodes, keeps its record and hands back once it is quorate again,
  rather than clear alerts with a view split from the majority's. Behind a cluster address, which
  is otherwise checked by ping and port 8006 alone, that costs one `/cluster/status` read, at the
  hand-back only. A record unspent after two hours expires, leaving those alerts to clear by hand.
- **S8, on the lab (2026-10-26):** with corosync stopped on Node-A at 14:32, Node-A's
  cluster modules read No Data on every poll and Node-B's collected on every poll with its own
  view (`NodesOnline 1`, `NodesOffline 1`, `Quorate 0`); after corosync returned at 14:56 the role
  went back. During the takeover Node-B no longer saw Node-A's guests as running
  (`GuestsRunning` 8 -> 1): from the surviving side, Proxmox reports the other node's guests as
  unknown, which Guest Status shows as not running (it ships without a threshold, so nothing pages).

**The role is a property, not a category** (user, 2026-10-25, after S5). Spike S5 showed that a NetScan re-run
overwrites a property's value but never removes a category it stopped sending (NetScan spec §10),
so a category cannot mark something that changes. Every node and the cluster resource carry
**`pve.cluster.role`** = `active`, `standby` or `none`, and the cluster modules apply on
`pve.cluster.role == "active" || pve.cluster.role == "standby"`. When a resource's role becomes
`none`, LogicMonitor detaches the cluster modules from it and deletes their history there. What
that loses is small: the old standby's history (a standby collects only while the active resource
is down), a node that left the cluster, or a cluster resource whose address was removed and which
would be dead without it. The alternative, keeping the modules attached and silent behind a role
check, kept that history but left idle modules and stale instances on the resource for good.

The pair is the lowest node IDs whether those nodes are online or not, so every NetScan run
picks the same pair and the role never moves on its own: a node being down is the standby's case,
and moving the role would move every cluster instance and its history (NetScan spec §5.1).

**A standalone host is one resource carrying both roles**, `ProxmoxVE_Cluster,ProxmoxVE_Node`.
This is the counterpart of vSphere's `standaloneEsxi` mode.

**Every node and the cluster address must answer the Collector's ping** (user, 2026-10-25, spec review). The same S2 finding
holds for node resources: their modules are scripts, so a node the Collector cannot ping is dead
in LogicMonitor, and its alerts are held back, however well its API modules collect. This is a
setup requirement; the NetScan checks it and reports a failure (NetScan spec §3 step 3), with the
same caveat about Java's ping.

**Categories are set by whoever creates the resource,** the NetScan or a user adding one by hand,
never inferred later from whether a token works, and they mark only what a resource *is*
(`ProxmoxVE_Cluster` on the cluster resource, `ProxmoxVE_Node` on a node, both on a standalone
host, `ProxmoxVE_Guest` on a guest): S5 showed a NetScan merges categories and never removes one,
so nothing that can change is a category. The token is set explicitly where it is needed:
the NetScan writes it onto cluster and node resources, as VMware's NetScan writes `esx.pass`. So
there is no stale category to clean up.

### 3.2 Which module goes where

**Node data lives on the node resource** (user ruling, 2026-10-24). This is a choice, not a
necessity: v1's per-node fan-out from one resource would still work, and the total is one call per
node per poll either way. What the split buys is that a node's disk, certificate and service alerts
are raised on that node: alert routing by node, an SDT on the node silences them, and dashboards
legend on `##HOSTNAME##` as VMware's ESXi widgets do. What it costs: the token on every node
resource (the NetScan writes it), the Collector reaching each node's 8006 directly, and the node
modules rewritten without their fan-out.

**Module names are new** (user ruling, 2026-10-24), split by role as VMware splits `VMware_vSphere_*`
from `VMware_ESXi_*`: `Proxmox_VE_Cluster_*` and `Proxmox_VE_Node_*`. None collides with a v1 name,
so v1 and v2 can be imported into one portal, as the lab's is (§3.5). **v2 is standalone** (user, 2026-10-25): it
neither needs v1 installed nor changes it. Every v2 module starts as a copy, in this repository,
of a v1 module at commit `2e0237b`, v1 complete (user, 2026-10-25):

| v2 module | Role | From v1 | Change | Calls per poll |
|---|---|---|---|---|
| `Proxmox_VE_Cluster_Status` | Cluster | `Proxmox_VE_Cluster` | Its one instance still carries the cluster vertex key (§3.4); it includes v1's migration counts. **Gains the cluster health score** (below): `NodesOnline`, `NodesTotal`, `NodesOnlinePercent`, `HealthScore` | 4 + 2 per online node (migration history) + 1 `rrddata` per online node (health), every 5 minutes |
| `Proxmox_VE_Cluster_Nodes` | Cluster | `Proxmox_VE_Nodes` | **Keeps only `Status`** (online). A node that is down cannot report itself down, so this stays on the cluster role; CPU, memory and uptime move to `Proxmox_VE_Node_Detail` | O(1) |
| `Proxmox_VE_Cluster_GuestStatus` | Cluster | `Proxmox_VE_GuestStatus` | AppliesTo only. Instance ERIs (guest MACs) unchanged | O(1), plus the hourly guest-config read in discovery |
| `Proxmox_VE_Cluster_GuestPerformance` | Cluster | `Proxmox_VE_GuestPerformance` | AppliesTo only | O(1) |
| `Proxmox_VE_Cluster_StorageCapacity` | Cluster | `Proxmox_VE_StorageCapacity` | AppliesTo only | O(1) |
| `Proxmox_VE_Cluster_Ceph` | Cluster | `Proxmox_VE_Ceph` | AppliesTo only | O(1) |
| `Proxmox_VE_Cluster_CephOSD` | Cluster | `Proxmox_VE_CephOSD` | AppliesTo only. Any node returns the whole CRUSH tree | O(1) |
| `Proxmox_VE_Cluster_Backups` | Cluster | `Proxmox_VE_Backups` | AppliesTo only. Keeps its loop over nodes for vzdump tasks: the view is cluster-wide | O(jobs + nodes) |
| `Proxmox_VE_Cluster_Snapshots` | Cluster | `Proxmox_VE_Snapshots` | AppliesTo only. Hourly, with v1's per-node circuit breaker | 2 + 1 per guest on an online node, hourly |
| `Proxmox_VE_Cluster_Topology` | Cluster | `Proxmox_VE_Topology` | AppliesTo only (§3.4) | O(1), plus the hourly guest-config read |
| `Proxmox_VE_Node_Detail` | Node | `Proxmox_VE_NodeDetail` | Single-instance; asks its own node. **Gains `CPUUsagePercent`, `CPUCount`, the memory trio and `UpTimeSeconds`** from the same `/nodes/{node}/status` call, so a node's performance alerts are raised on the node (the reason for this section) and each datapoint still lives in one module, as v1 DESIGN §4 requires. **Also gains** the node's pressure figures (`PressureCPUSomePercent`, `PressureIOSomePercent`, `PressureIOFullPercent`, `PressureMemorySomePercent`, `PressureMemoryFullPercent`, from `/nodes/{node}/rrddata`) and **its own view of the cluster** (`Quorate`, `NodesOnline`, from its `/cluster/status`). Its `Quorate` alerts, as "this node has lost quorum", which is per node; its `NodesOnline` is recorded without a threshold, since the active and standby cluster role already alert on nodes going offline, and 16 copies of that alert would bury it (user, 2026-10-25, spec review) | 3 per node |
| `Proxmox_VE_Node_Disks` | Node | `Proxmox_VE_Disks` | Fan-out removed; asks its own node | O(1) per node |
| `Proxmox_VE_Node_Services` | Node | `Proxmox_VE_NodeServices` | Fan-out removed | O(1) per node |
| `Proxmox_VE_Node_Certificates` | Node | `Proxmox_VE_Certificates` | Fan-out removed | O(1) per node |
| `Proxmox_VE_Node_Subscription` | Node | `Proxmox_VE_Subscription` | Fan-out removed | O(1) per node |
| `Proxmox_VE_Node_Replication` | Node | `Proxmox_VE_Replication` | Fan-out removed. Node-scoped: its endpoint is `/nodes/{node}/replication` | O(1) per node |
| `Proxmox_VE_Info` (PropertySource) | Cluster, Node | `addCategory_Proxmox_VE` | Keeps the `auto.pve.version` / `release` / `clustered` reads; **sets no category** (§3.1) | — |
| `addERI_Proxmox_VE_Node` (ERI) | Node | `addERI_Proxmox_VE` | AppliesTo `hasCategory("ProxmoxVE_Node")`, replacing the `-pve` kernel and `pve.topology.node` selectors | — |
| `addERI_Proxmox_VE_GuestResource` (ERI) | Guest | `addERI_Proxmox_VE_Guest` | New name, so v2's import leaves v1's alone (standalone, user 2026-10-25). Extended to read `pve.guest.mac` (§3.6); where both are installed, both stamp the same MAC key, which merges | — |
| — | Guest | — | No Proxmox API module in phase 1. In-guest monitoring (SNMP, WMI, SSH) applies by hostname, as for any resource | 0 |

Node-local storage stays in `Proxmox_VE_Cluster_StorageCapacity`, which already reads every store,
local or shared, in its one call.

**Cluster modules apply wherever the cluster role is,** `pve.cluster.role == "active" ||
pve.cluster.role == "standby"` (§3.1): the cluster resource and a node, or two nodes. Each starts
with the preamble's role check and prints nothing unless it is the resource that should collect.

**The cluster health score** (user's proposal, 2026-10-25; inputs checked on the lab's API the
same day). `/cluster/resources` gives each node's `cpu`, `maxcpu`, `mem`, `maxmem` and `status` in
one call, but no load or IO wait; `/nodes/{node}/status`'s `cpu` and `wait` are one-moment samples
(both read `0` on the lab); `/nodes/{node}/rrddata` gives per-minute averages including Linux
pressure stall information, in percent of time stalled (Node-A: CPU 2.5, IO 0.37; Node-B:
CPU 0, IO 0.29). Pressure replaces load per core and IO wait as the node input: it is already
relative to the machine's size, and load average already counts tasks waiting on IO, so weighting
both would count one stall twice.

```
node score     = 0 if the node is offline,
                 else 1 − max(cpu some, io some, memory some pressure) / 100
HealthScore    = 0 if the cluster is not quorate,
                 else 100 × (NodesOnline ÷ NodesTotal) × mean(node scores)
```

`max` keeps one starved resource from being averaged away; an offline node lowers the score twice,
in the ratio and in the mean, as it does for its guests. **`Quorate` and `NodesOnlinePercent`
carry the alerts; `HealthScore` a warning threshold only,** since a blended number cannot say what
is wrong. The weights are a starting point to tune. Whether Proxmox VE 8 returns the pressure
fields is [unverified]; without them a node's input falls back to load average ÷ cores.

**How a node module finds its own node:** the `local` row of `/cluster/status`, which
`pveTopoClusterName` already reads in v1, so no property is set by hand; a NetScan-created node
also carries `pve.node` (NetScan spec §5.2), which is read first. The node API modules apply on
`hasCategory("ProxmoxVE_Node") && pve.netscan.reachable != "false"`, so a node the Collector
cannot reach raises no collection errors (NetScan spec §5.2); a manually added node, which has no
such property, is included. The node modules use the
node resource's own hostname and **never fail over** through `pve.api.hosts`: another node's
`local` row would report the wrong node. If `/nodes/localhost/...` is accepted over HTTPS, it saves
that call; S4 checks it as an optimisation, nothing depends on it.

**Guest hypervisor metrics stay instances on the cluster role,** which is what VMware does:
LogicMonitor's `VMware_vSphere_VirtualMachineStatus` export applies on
`system.virtualization =~ "VMware ESX vCenter"`, the vCenter resource only, and its
`VMware_ESXi_VirtualMachinePerformance` on a host only with `standaloneEsxi =~ "true"` (exports
read 2026-10-25; Q1 answered). Guest instance wildvalues stay `pveWildValue(id)`, so a migration
keeps a guest's history.

**Dashboards** carry over from v1's Tier 1 and Tier 2 with the module names replaced. The node
widgets are rebuilt to legend on `##HOSTNAME##`, since each node is now its own resource.

**Scale** (Q6: 10 clusters of 16 nodes and 1,000 guests) changes how the per-guest and per-node
loops are written, not where the modules go: §3.9.

### 3.3 The NetScan

**Designed in its own spec: `2026-10-25-netscan-design.md`.** In brief: an Enhanced Script NetScan
modelled on VMware's (research §2), one per cluster, run hourly. It reads `/cluster/status` and
`/cluster/resources`, checks that the Collector reaches each node directly, and writes the cluster
role, every node and (opt-in, filtered) guests into `<rootFolder>/Cluster - <name>/Nodes|Guests`,
never grouping guests by node. It writes the token onto cluster and node resources only, and the
node name, cluster name and failover list (`pve.api.hosts`) as properties, so no property is set
by hand. With LogicMonitor API credentials it adopts existing devices by hostname, display name
or (guests) MAC instead of duplicating them, and lists orphans; it never deletes. Its
Proxmox logic is a pure preamble function the harness tests; only the `lm.emit` / `lm.api` calls
are Collector-only.

**Manual adds** set the category and the properties by hand, documented per role, as VMware
documents "Manually Assigning Properties to Resources".

### 3.4 Topology

> **Amended 2026-10-28 (user, the vSphere topology model).** The map is modelled on LogicMonitor's
> vSphere suite, with Proxmox's own depth added (research
> `docs/research/2026-10-28-lm-vsphere-topology.md`). The user chose, 2026-10-28: parity plus
> Proxmox depth (pools, disks, bridges, SDN), with new carrier DataSources; vSphere's vocabulary
> (Manage edges, `HyperVisor`; the cluster stays `Cluster`, below); every store a `StorageVolume`, local stores one
> vertex per node, shared and network stores one per cluster, the kind in the instance name; guest
> to store directly, without virtual-disk vertices.
>
> - **Edges.** Manage: cluster → node. Compute: node → guest. Storage: node → store; guest → store
>   (from `rootfs`, `mpN`, `scsiN`, `virtioN`, `sataN`, `ideN`, `efidiskN`, `tpmstateN`; a bind
>   mount joins the store whose directory holds it); node → guest for a host path or passed-through
>   device no store covers; store → ZFS pool, LVM thin pool, volume group, Ceph pool or (a directory
>   store on a Proxmox-made mount) disk; thin pool → volume group → disk; ZFS pool → disk; node →
>   Ceph OSD → disk. Network: SDN only, node → zone → VNet → guest. Bridges, ports, bonds and VLAN
>   interfaces are kept off the map (user, 2026-10-28): a bridge repeats what the Compute edge says, a
>   switch joins at the node by the MACs on its resource, and the node's SNMP interface monitoring
>   covers its ports. `Node_Network` monitors bridges only and carries no keys. Not drawn: node uplink → physical switch (Proxmox gives no interface MAC or LLDP),
>   guest disks as vertices, backup targets.
> - **Vertices and carriers** (`pveTopoVertexKey`, `proxmoxve--<cluster>--…`): stores on
>   `Cluster_StorageCapacity` (`StorageVolume`, not vSphere's `VMDatastore`: on the lab, 2026-10-28,
>   store vertices typed `VMDatastore` resolved to their instances but took no edges, before and after
>   the instances were deleted and rediscovered, and took every edge once typed `StorageVolume`; a
>   shared store's key on one instance only, the first
>   node's by name); pools on the new `Node_StoragePools` and `Cluster_CephPools`
>   (`StorageAggregate`); OSDs on `Cluster_CephOSD` (`StorageNode`); disks on `Node_Disks`
>   (`PhysicalDisk`); SDN zones and VNets on the new `Cluster_SDN` (`VirtualSwitch`). Cluster modules
>   stamp keys only where they collect; node modules read the cluster name from their own node.
>   With `topo.namespace` or `topo.blacklist` set, carriers stamp nothing, as before.
> - **Where it is decided.** `pveTopoInput` (the reads) and `pveTopoPlan` (the edges) in the
>   preamble; the TopologySource only turns keys into ERIs. Cost per hourly run: a handful of
>   cluster reads, six or so per online node plus one per ZFS pool, and the guest-config reads it
>   already made.
> - **The cluster vertex's key is `proxmoxve--cluster--<cluster>`** (`pveTopoClusterKey`), no longer
>   `proxmoxve--<cluster>`: the portal keeps one vertex record per key, which outlives the instances
>   carrying it and kept the type it was first drawn as. Retyped `HyperVisorCluster`, and with its
>   instance deleted and rediscovered, the lab's cluster vertex kept its ID and its type `Cluster`
>   (lab, 2026-10-28); a new key is the only way to a new record. Under the new key the fresh
>   record took `HyperVisorCluster` from its instance and **took no Manage edges** across the topology
>   runs after the import, as `VMDatastore` took none; so the cluster is typed `Cluster`, the type that
>   drew it with every Manage edge since 2026-10-26.
> - **Volume groups** report `AllocatedPercent` without a threshold, never `UsedPercent`: the Proxmox
>   installer allocates nearly all of one by design, so it read 97% and alerted on the lab (user,
>   2026-10-28). Thin and ZFS pools keep `UsedPercent` and its alert.
> - **Unverified** until the lab sees it: that `HyperVisor` renders and the
>   node takes `HyperVisor` at priority 3 over `net.l2`; that the new carriers' instance vertices draw
>   and join the plan's edges; ZFS, Ceph and SDN (the lab has none).

- **Node vertices:** each node resource's own ERI, using v1's name-plus-IP key
  (`pveTopoNodeVertexKey`) and still exactly one key per vertex (v1 DESIGN §7, 2026-10-21).
- **Guest vertices:** the MAC, as in v1.
- **Cluster vertex:** unchanged from v1: `Proxmox_VE_Cluster_Status`'s one instance carries
  `proxmoxve--<cluster>`, type `Cluster`, and the map draws it with Cluster edges to every node
  (v1 spike 3 re-run, 2026-10-22). This holds whichever resource carries the cluster role, so Q8
  does not affect the map, and no cluster ERI PropertySource is needed.
- **With a standby (§3.1),** discovery runs on both resources, so the standby already holds every
  instance when it has to collect: a standby whose discovery printed nothing would have no
  instances until its next discovery, up to an hour later. Only the active resource's discovery
  stamps the cluster key on its instance, and only the active resource's TopologySource prints, so
  the map keeps one cluster vertex. After a takeover the key moves at the standby's next
  discovery. How the map draws the hour in between is [unverified, S8].

### 3.5 No migration from v1

**There is no migration path, by design** (user, 2026-10-25): neither version has any users, so
v2 is installed fresh and nothing converts v1's instances, history or properties.

One consequence remains, for testing only: the lab portal runs v1. v2's token property is
`pve.token.credential`, never v1's `pve.api.token.credential`, because v1's
`addCategory_Proxmox_VE` applies on `true()` and tags any resource holding v1's property with
`ProxmoxVE`, which applies all of v1 to it [v1, `pve_common.groovy` 62–69]. The new module names
(§3.2) keep the two from replacing each other on import. With both in one portal, the cluster key
`proxmoxve--<cluster>` sits on a v1 and a v2 instance, and both node ERI modules stamp the same
node key; the node keys merge, and the doubled cluster key's drawing is untested, which matters
only in the lab.

### 3.6 When a guest should be a resource

The user's position (2026-10-21): a guest needs to be a resource only for what can be seen from
inside it — an application, a service, a process, events or logs. Checked against LogicMonitor's
documentation:

| Need | An instance on the cluster role | A guest resource | Source |
|---|---|---|---|
| Hypervisor-side metrics (CPU, memory, disk and network I/O, status) | yes | no extra | v1, 0 HU |
| In-guest monitoring (applications, services, processes, logs, events) | no | **yes** | the user's point |
| Scheduled downtime per guest | **yes**: SDT is settable on an instance | yes | [Instance Tab](https://www.logicmonitor.com/support/instance-tab), [SDT Tab](https://www.logicmonitor.com/support/devices/devices-page-overview/scheduled-down-time-sdt-tab) |
| Access control per guest or tenant | **no**: role permissions are set on resource groups only; instances get threshold rights alone | **yes** | [Resources Role Permissions](https://www.logicmonitor.com/support/resources-role-permissions) |
| Alert routing by guest | by instance name in an alert rule [unverified] | yes, by group | — |
| Drawn under its node on the topology map, on a cluster | **yes, verified**: the instance-only `CT-Test` on the token-less `Node-B` was drawn under `Node-B` from an instance on `Node-A` (v1 DESIGN §7, 2026-10-22). An earlier draft said "not shown", citing spike 3's first run, which the re-run reversed | **yes, verified**: `Node-B` → `Debian-Test`; where a resource and an instance carry the same MAC, the edge reaches the resource (v1 DESIGN §7, 2026-10-22) | v1 portal checks |

So the user's position holds, with one exception: **multi-tenant access control** (an MSP, or a
team that owns some guests) needs guests as resources in groups. The map is not a reason to make a
guest a resource.

**Guest resources stay opt-in and filtered,** as in VMware's NetScan. A guest that is already a
resource through SNMP, SSH or WMI is adopted by deduplication, not duplicated. A NetScan-created
guest resource carries its MAC from the guest config (`pve.guest.mac`), and
`addERI_Proxmox_VE_GuestResource` stamps it, so the map's guest vertex opens that resource rather than
the instance, with no in-guest access needed. It stamps the bare MAC, the vertex's own key, beside
`proxmoxve--vm--<mac>`: the first version stamped only the latter, and on the lab map the NetScan's
`Webapp01` stood alone beside its instance vertex (lab map, 2026-10-26). Its cost, 1 HU, is the user's choice, per guest or
per filter. **A guest the NetScan finds no address for is not created by default** (user, 2026-10-25, spec review): its
hostname would be its name, which does not answer, so it would be dead from the start, as S2's
`Migration-Test` was. The run reports it instead; `pve.netscan.guests.unaddressed=true` creates
such guests anyway (NetScan spec §5.3).

### 3.7 Licensing (from the user, 2026-10-21)

| Object | Host units |
|---|---|
| A cluster's management endpoint added as a resource (vCenter in VMware) | 1 |
| Each hypervisor host added as a resource | 1 each |
| Each VM added as its own resource | 1 each |
| Datastores, clusters, networks, resource pools, snapshots: instances or groups under a resource | 0 |
| VMs seen only through the cluster-level VM modules, not added as resources | 0 |

**What this means for Proxmox:**
- **Nodes:** 1 HU each. They are resources in v1 already, since the map needs them.
- **Guests as instances:** 0 HU. This is the default.
- **Guest resources:** 1 HU each, so opt-in only.
- **A separate cluster resource is one new HU per cluster** that v1 does not spend: v1's
  token-holding node doubles as the cluster. Since S2 (§3.1) it exists **only where the cluster
  has an address of its own**; without one, the cluster role sits on an active and a standby node,
  0 HU, as in v1. The catch of the node columns: the cluster-level instances live on node
  resources, and leave with them if those nodes are retired (the install guide says how to move
  the role first). So v2 is the middle column for a cluster without an address, the right-hand
  column for one with a VIP, proxy or DNS name.

#### v1 against v2, in host units (2026-10-25)

From the table above: 1 HU per resource, 0 per instance. *N* is the nodes in a cluster, *G* its
guests, *S* the guests selected for guest resources, and *A* the guests that are already resources
through SNMP, WMI or SSH. Those *A* cost 1 HU each under both versions alike, and v2's
deduplication adopts them, so selecting them adds nothing; the rows below take them to be among
the selected.

| Deployment | v1 | v2, no cluster address: role on two nodes | v2, cluster address: separate cluster resource |
|---|---|---|---|
| Standalone host | 1 | 1 | 1 (a standalone host never has a dedicated resource) |
| Cheapest possible, per cluster | **1**: the token holder alone collects everything, node-level modules included, by fan-out; only the other nodes are missing from the map | **2** (`pve.netscan.nodes=false`): only the two role nodes, which carry the node modules for themselves and the cluster-level data; the other nodes are missing. One resource would leave no standby, so a node failure would silence the cluster (user, 2026-10-25, spec review) | **2**: the cluster resource and its standby node |
| As documented (every node a resource) | *N* (v1 INSTALL §3.3, for the map) | *N* | *N* + 1 |
| + guests as resources | *N* + *A* (v1 creates none; *A* are the user's own) | *N* + *S* | *N* + 1 + *S* |
| Lab: 2 nodes, 9 guests, guests off, none SNMP | 2 | 2 | 3 |
| One target cluster: 16 nodes, 1,000 guests, guests off, none SNMP | 16 | 16 | 17 |
| The same, 10 guests already SNMP, 50 selected (those 10 among them) | 16 + 10 = 26 | 16 + 50 = 66 | 67 |
| The same, 10 guests already SNMP, every guest selected | 26 | 16 + 1,000 = 1,016 | 1,017 |

**At its defaults v2 costs exactly what v1 costs as documented**, or one more per cluster that
has its own address. The licence cost of v2 is **opt-in**, and is entirely guests: *S* minus *A*,
the guests already monitored.

**At the scale target (Q6: 10 clusters of 16 nodes and 1,000 guests):** 160 HU under v1 as
documented; **160 under v2** with guests off and no cluster addresses, up to **170** if all ten
have a VIP, proxy or DNS name. Every guest selected adds up to 10,000 HU, less the guests already
monitored.

**Why one might be preferred:**

- **v1, when the licence budget is the constraint.** One resource per cluster still collects every
  node's disks, certificates, services and detail, because it fans out from the token holder. v2
  cannot match that at 1 HU: its node modules need node resources. v1 is also the simpler install
  (one property, no NetScan, no LogicMonitor API credentials, the Collector needs to reach one
  address).
- **v2, when the resources are worth their units.** At the same *N* HU it adds what v1 cannot:
  node alerts raised, routed and silenced on the node itself; cluster monitoring that survives the
  loss of any one node; no hand-set token, category or topology property, and no stale category
  when a host joins a cluster; and, for the guests someone selects, access control per tenant and
  in-guest monitoring organised by cluster and pool — at 1 HU per guest not already monitored.
- **The risk in v2 is a single switch.** `pve.netscan.guests=true` with no filter on one cluster
  of the scale target is 1,000 HU at the next hourly run. The default is off, the install guide pairs the switch
  with a filter, and the NetScan's output states how many guest resources the run would create.
- **API load is not a differentiator.** The total is the same either way (one bulk call per
  cluster-level module, one call per node per node-level module); v2 adds the NetScan's hourly
  2 + *N* calls, plus 2 per selected guest.

### 3.8 Carried over from v1

**Everything is copied into this repository** from v1 commit `2e0237b` (v1's tag `v1.0.0`; `dca3f23` since v1's history rewrite, 2026-10-27) (v1 complete; the copy is tagged `v1-base`), the commit
recorded here, as the skill pins its fixtures: all 19 modules' scripts and definitions as the
starting point of §3.2's rows, and the pieces below unchanged except where stated (user,
2026-10-25: v2 is standalone and customises its own copy). **Done 2026-10-25,** at the user's
request: copied verbatim with `git archive 2e0237b`, v1's documentation under `docs/v1/`, so every
later change shows as a diff against v1. Checked the same day: every one of v1's 71 tracked files
matches its copy; the build output is byte-identical to v1's and to the skill's portal-verified
fixtures; the Docker compile job and the harness (1,378 checks) pass. That includes what v1 added after `e6873b7`: graphs on every
DataSource and the build's `check_graphs`, `pveSizeLabel` sizes in instance names and descriptions,
and the rule that end-user text points at Proxmox's own views because stderr on a successful run is
not shown (v1 DESIGN §7). v1 keeps its own history; a later v1 fix reaches v2 only by copying it over.

- `scripts/lib/pve_common.groovy`: properties, TLS, HTTP, `pveWildValue`, the emit and discovery
  helpers, the topology key and guest MAC helpers. The token is read from `pve.token.credential`
  (§3.5); the other `pve.api.*` names stay, since v1 keys on none of them. Additions: the role
  check every cluster module begins with (§3.1); failover across `pve.api.hosts`, used by the
  cluster role only (§3.2); per-node requests addressed to the owning node, bounded concurrency and
  a time budget for the O(guests) loops (§3.9).
- `build/` (`build.py`, `dashboards.py`, `groovylint.py`), `tests/` (the Docker compile job, the
  harness and its mock API), the dashboard validation and the lint run order. The build's
  module-name and category checks change with §3.1 and §3.2.
- v1 DESIGN §1–§6: naming, collection by BatchScript over `/cluster/resources`, Proxmox `id` as
  wildvalue, `derive` counters, template filtering, the cost tiers.
- Every portal lesson recorded in v1 DESIGN §7 and in the skill.

### 3.9 Scale: 10 clusters × 16 nodes × 1,000 guests (Q6, user 2026-10-25)

The target the design is built for, though the lab (2 nodes, 9 guests) cannot test it. The user's
rule: as scalable as realistic real-world deployments need.

**The steady load is small.** From v1's own intervals and per-module costs (v1 CLAUDE.md), per
cluster per hour:

| Where | Calls/hour | Of which O(guests), hourly |
|---|---|---|
| Active cluster role (the cluster resource or a node) | ~2,550 (with the shared config read below; ~3,550 without), + 192 for the health score's `rrddata` reads at 16 nodes, + ~180 for the role check on a node (none on a cluster resource) | ~2,000: one guest-config read (GuestStatus discovery and Topology together) and one snapshot read per guest |
| Standby (§3.1) | ~210: the role check on each of the ~89 cluster-module polls an hour, two calls to itself (`/cluster/status`, `/nodes/<self>/services`), none behind a cluster address, which is checked by ping and port 8006; and the cluster modules' hourly discoveries, which on a standby list without reading guest configs (code, 2026-10-26: the role check is not shared through `lm.cache`, as an earlier draft said) | 0 |
| Each node resource | ~55, to itself: ~31 as in v1, + 24 for the pressure and quorum reads of `Node_Detail` | 0 |
| 16 nodes | ~880 | 0 |
| NetScan, guests off | 18 | 0 (up to ~2,000 with every guest selected) |
| **One cluster** | **~4,000, about 1 a second** | |
| **Ten clusters** | **~40,000**, spread over their Collectors | |

The largest 5-minute cost is `Proxmox_VE_Cluster_Status`'s migration history, 2 calls per node
every 5 minutes (~430 an hour at 16 nodes); acceptable, and the first to move to a longer interval
if a site needs it.

**What limits scale is not the average but the hourly per-guest loops:** ~1,000 calls in a row
inside one script run, against one API node, which is also a production hypervisor. So v2 builds
these rules into every O(guests) and O(nodes) loop:

1. **One guest-config read per hour, not two.** v1's backlog item (v1 DESIGN §4) is built in v2:
   whichever of GuestStatus discovery and the TopologySource runs first caches each guest's config
   through `lm.cache`, and the other reads the cache, falling back to the API on a miss. Saves
   ~1,000 calls an hour per cluster. Needs Q4 (S7).
2. **Ask the node that owns the object.** A per-guest or per-node read (`/nodes/<n>/...`) goes
   straight to node `<n>`'s address from `pve.api.hosts`, not through the API address, which would
   proxy it there anyway. That spreads the hourly ~2,000 calls over 16 nodes instead of one, and
   halves their hops. The Collector already has to reach every node for the node modules (§3.2).
   A node it cannot reach falls back to the API address.
3. **A per-node circuit breaker** in every loop, as v1's Snapshots has: one failure skips the rest
   of that node's objects for the run, so a sick node costs one timeout, not 60.
4. **Bounded concurrency** for O(guests) loops: `pve.api.concurrency`, default 2, so 1,000 reads
   take half the wall time without crowding the node's own API workers (pveproxy and pvedaemon
   run a small fixed pool each, 3 by default [unverified on 9.2]), which the Proxmox UI and its
   users share. Rule 2 already spreads the load, so 2 per node in flight is the ceiling.
5. **A time budget** inside every O(guests) loop, below the Collector's script timeout (its value
   for each module type is [unverified]; S2 reads it). If the budget runs out, the run emits what
   it has, says so, and withholds the rest: no fabricated values. If S2's per-call latency says
   1,000 guests would not fit, the loop spreads the guests over several runs with a cursor in
   `lm.cache`. That is designed, and built only if measured. The same budget bounds
   `Proxmox_VE_Cluster_Status`'s per-node loops (rrddata for the health score, task history for
   migrations), which are O(nodes) but cost a timeout each for a node that hangs (final review,
   2026-10-26).
6. **A request for one node fails over once at most.** A `/nodes/<name>/...` path is answered
   by that node, whichever address proxies it, so trying every address in `pve.api.hosts` for a
   node that hangs costs a timeout per address; the cluster role tries two (final review,
   2026-10-26).

**On the LogicMonitor side:** each cluster-level guest module holds 1,000 instances on its
cluster resource, and Backups one per guest per job. Whether the portal limits instances per
module is [unverified]; LogicMonitor's own VMware modules carry every VM of a vCenter the same
way. One Collector, or an auto-balanced Collector group, per cluster keeps each cluster's load
apart, as LogicMonitor advises for vSphere (research §2). Deduplication on a portal of ~10,000
devices takes VMware's one-query-per-resource path (NetScan spec §6).

**Tested how:** the harness gets a generated fixture of 16 nodes and 1,000 guests, and asserts
the call count per module against this table, the breaker, the budget and the concurrency bound,
so a change that adds an O(guests) call fails locally. The latency side needs a real cluster:
S2 measures the lab's per-call time; the scale itself is for end-user review, as v1 recorded
for its own scale claim (v1 DESIGN §7).

## 4. Open questions

The spikes in §5 answer Q3–Q4, run together with the user. The user's questions, Q6–Q9, are
answered (2026-10-25).

- **Q1. Answered** 2026-10-25 (§3.2): VMware's VM modules apply to the vCenter resource; per-host
  VM modules exist only for standalone ESXi.
- **Q2. Answered** by S2 (2026-10-25): an Enhanced Script NetScan creates the resources, their
  properties and the group path it names (NetScan spec §10).
- **Q3.** Does `lm.api` deduplication adopt an existing SNMP or WMI guest resource, or only report
  it? And can MAC matching be added?
- **Q4.** Does `lm.cache` share data between a TopologySource and a DataSource's discovery on one
  Collector? Needed at the scale target for the single guest-config read (§3.2).
- **Q5. Licensing:** answered in §3.7. `pve.netscan.guests` defaults to off.
- **Q6. Scale target: answered** — 10 clusters of 16 nodes and 1,000 guests each (§3.7, §3.9).
- **Q7. Where the token lives: answered** — one Proxmox API token per cluster (user, confirmed
  2026-10-25), held by that cluster's NetScan and written onto its cluster and node resources
  (NetScan spec §7).
- **Q8. The cluster role: answered, then revised** — first a separate cluster resource (user,
  2026-10-25); after S2 showed such a resource is dead without an address of its own, an active
  and a standby resource: the cluster resource and a node where the cluster has a VIP, proxy or
  DNS name, two nodes where it has none (user, 2026-10-25; §3.1, §3.7).
- **Q9. Node hostnames: answered** — the node's `/cluster/status` address by default, the name
  as an option (NetScan spec §5.2).

## 5. Spikes, before any plan

| Spike | Answers | Method | Cost |
|---|---|---|---|
| S1 | Q1 | read the VMware VM modules' AppliesTo | **done** 2026-10-25, from LogicMonitor's exports |
| S2 | Q2 | a throwaway NetScan against the lab cluster that creates resources in a test group; detail in NetScan spec §10. **Run 2026-10-25:** resources and groups created; a cluster resource without an address is dead whatever collects on it (§3.1) | one import, one run, then delete the group |
| S3 | Q3 | the same NetScan run against the v1 node resources and a guest already monitored over SNMP | **Done 2026-10-26** on the lab (NetScan spec §2, §6, §10): with every guest selected and `hostname.source` unset, all six guests already monitored in-guest were caught by address and not sent; with `hostname.source=lm` and *All matching resources*, the SNMP guest Nvr01 was adopted (properties, `ProxmoxVE_Guest`, the Guests group, same device). Re-runs rewrite values on the NetScan's own resources under the defaults; a manual save detaches a resource from its NetScan and it is still updated. Nvr01 kept its own categories beside `ProxmoxVE_Guest` |
| S5 | NetScan re-runs | NetScan spec §10. **Done 2026-10-25:** a re-run overwrites values and adds properties, categories and groups, but never removes any; the cluster role became a property (§3.1) | four runs of a throwaway NetScan |
| S6 | guest-address privileges | NetScan spec §10. **Done 2026-10-25:** `PVEAuditor` suffices on 9.2.3 | API calls from a workstation |
| S7 | Q4 | a throwaway TopologySource and DataSource on one Collector, one writing an `lm.cache` key and the other reading it | **Half done 2026-10-26:** `lm.cache` keeps a value between runs (Collector debug console), which the hand-back uses. Whether a TopologySource and a DataSource share it is **deferred** (user, 2026-10-26): it only decides rule 1's single guest-config read, an optimisation the scale fixture shows is not needed |
| S4 | failover; `/nodes/localhost` | the preamble with two addresses, the first unreachable; and one `GET /nodes/localhost/status` over HTTPS | **Done 2026-10-26** on the lab: with pveproxy stopped on Node-A (the active node), its cluster modules kept collecting through Node-B's API, Node-B stayed quiet, and Node-A's Node Detail went quiet (node modules never fail over). `/nodes/localhost` was not needed: nodes are named by `pve.node` |
| S8 | the standby (§3.1): does it take over when the active node is shut down, and do its open alerts clear when it goes quiet again? **Done 2026-10-26** on the lab, by stopping corosync on Node-A (16:41-16:47 for the final run): one collector at a time, the standby reporting its own view, every alert it raised (Cluster Status `Quorate`, `NodesOffline`, `HealthScore`, `NodesOnlinePercent`; Cluster Nodes `Node-A` Status; Node Detail `Quorate`) firing, and all of them clearing through one hand-back poll after Node-A returned. Found on the way and fixed: a node with corosync down reports a stale view (own-corosync check), a standby read the stale node through `pve.api.url` (own API first), a two-node standby never had quorum (two-node rule), and No Data never clears an alert (hand-back) | the built `Cluster_Status` on the lab's two nodes; shut down the active one, raise an alert on the standby, start the active one again | one import, one node restart |

**Verification by layer.** What each layer (local checks / portal import / live target) has
proved, the suite's one record of it (moved here from CLAUDE.md, user 2026-10-29). Add a row when
a layer changes; spike results stay in their rows above.

| Layer | Date | What it proved |
|---|---|---|
| Portal import | 2026-10-26, 2026-10-27 | Every module imports, through `tools/Import-Modules.ps1` and the portal for the PropertySources; the NetScan runs on the Collector, the trimmed 29,784-byte build included; the dashboards in `dashboards/` are the portal's exports of the final layout (user, 2026-10-27). Every module re-imported 2026-10-27 with the review's notes (user). The status-names build re-imported too (user, 2026-10-27): the names show on the datapoints, and an alert on a stopped guest's `Status` (a temporary `< 1` threshold) read "Status: Not running (0)" |
| Live target: `Labcluster` (two nodes, PVE 9.2.3, no HA, no Ceph, no replication) | 2026-10-26 | S2, S3, S4, S5, S6 and S8 done (rows above): the NetScan creates Node-A (active), Node-B (standby) and the guest Webapp01, and a re-run adds nothing and leaves the expected properties; the standby takes over when corosync or pveproxy stops on the active and hands back with its alerts cleared. The guest ERI's bare-MAC key joins a NetScan-created guest to its map vertex: Webapp01, drawn alone beside its vertex before the fix, is drawn once, under Node-A (user, 2026-10-26). After S3's Nvr01 adoption, a run with every guest selected adopted the other five (Media01, audio01, Relay01, Kiosk01, automation01: one `adopted:` line each in the report, portal names kept, Nvr01's too, so the `lm` display-name fix holds). Unexplained: three runs that missed Node-A's value while it was attached. S7's sharing half is deferred (user, 2026-10-26) |
| Local checks | 2026-10-27 | After the review fixes, the lab-detail scrub and the status names: build clean; compile job passed; harness 1,489 of 1,489; lint 0 errors, 4 warnings; both dashboard exports check clean |
| v2.1.0, the vSphere topology model: local checks | 2026-10-28 | §3.4 amended; merged from `topology-vsphere-model`. Build clean (23 modules); compile job passed; harness 1,856 of 1,856; lint 0 errors, 4 warnings; both dashboards check clean |
| v2.1.0: portal import and live target | 2026-10-28, 2026-10-29 | Imported and run on the lab (user, 2026-10-28): nodes draw as `HyperVisor`; node -> store, every guest -> its stores (Media01 -> its root store, and -> a USB directory store through its bind mount), store -> thin pool -> volume group -> disk; bridges, ports, bonds and VLAN interfaces stay off the map (user, 2026-10-28); volume groups report `AllocatedPercent` with no alert. **Store vertices typed `VMDatastore` took no edges** -- before and after their instances were deleted and rediscovered -- and took every edge once typed `StorageVolume`. The portal keeps a vertex record per key with its first type; a fresh cluster record typed `HyperVisorCluster` took no Manage edges, so the cluster is `Cluster` under a key of its own shape, `proxmoxve--cluster--<name>`: confirmed, a new record with both Manage edges on the first run after the retype (lab, 2026-10-28), and the old `proxmoxve--<name>` vertex is drawn nowhere: the portal's `/topology/data` around both nodes and the cluster instance holds one `Cluster` vertex, the new key's (lab, 2026-10-29) |

**Not yet seen on the lab:** Replication, Ceph, ZFS and SDN (the lab has none), a cluster address
(VIP/DNS), a large portal's one-at-a-time lookups, adoption under the default duplicate-IP
exclusion.

## 6. Not in scope

- Per-guest Proxmox API modules on guest resources. Phase 1 is guests as resources for
  organisation and in-guest monitoring.
- LogSources, ConfigSources and the troubleshooter module. They are good later additions; VMware
  ships all three.
- Deleting resources from the NetScan.
