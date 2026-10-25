# Proxmox VE suite v2: cluster, node and guest resources

Status: **draft for review**, 2026-10-21; revised 2026-10-24 with the v1 reuse decisions (§3.2,
§3.8) and topology corrections (§2, §3.4, §3.6); revised 2026-10-25 for v1 complete at `2e0237b`,
the NetScan spec (§3.3) and the v1-against-v2 licence comparison (§3.7); revised again 2026-10-25:
v2 is standalone, with its own token property `pve.token.credential` (§3.2, §3.5, §3.8). Nothing here is approved or built. Research behind it:
`docs/research/2026-10-21-lm-vsphere-architecture.md`. v1 is the `Proxmox-LogicModule` repository
(18 modules, portal-verified on a two-node lab cluster).

## 1. Goal

Monitor Proxmox VE the way LogicMonitor monitors vSphere, so that the suite stays usable and
efficient at enterprise scale (the working example is 12 nodes and 500 guests):

- **Every node is a resource with its own node-level data**, not an instance on one node's resource.
- **Cluster-level monitoring does not depend on one node being up.**
- **Guests can be resources**, created and kept current by a NetScan, organised in groups, with
  their node available as a property. This is optional.
- **API cost stays O(1) per poll for cluster-wide data**, as in v1 (v1 DESIGN §2).
- **No manual property-setting per node or per guest**, and no stale state when a node joins a
  cluster or a token moves.

## 2. Why v1 falls short

- **Everything hangs off the token holder.** One node's resource carries every node, guest and
  storage object as instances. With 500 guests that list is unreadable, and alert routing,
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

**The cluster resource is not a node** (Q8, user 2026-10-25: a separate resource). Its hostname
is the address the user gives the NetScan, a VIP or a DNS name; where that address is a node's,
it is the cluster name instead, so it never collides with the node resource (NetScan spec §5.1).
It carries `pve.api.url` and `pve.api.hosts`, every node's address from `/cluster/status`, and the
preamble fails over through that list; the cluster modules never use the hostname. This removes
the single point of failure.

**A standalone host is one resource carrying both roles**, `ProxmoxVE_Cluster,ProxmoxVE_Node`.
This is the counterpart of vSphere's `standaloneEsxi` mode.

**Categories are set by whoever creates the resource,** the NetScan or a user adding one by hand,
never inferred later from whether a token works. The token is set explicitly where it is needed:
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
so v1 and v2 can be imported side by side (§3.5). **v2 is standalone** (user, 2026-10-25): it
neither needs v1 installed nor changes it. Every v2 module starts as a copy, in this repository,
of a v1 module at commit `2e0237b`, v1 complete (user, 2026-10-25):

| v2 module | Role | From v1 | Change | Calls per poll |
|---|---|---|---|---|
| `Proxmox_VE_Cluster_Status` | Cluster | `Proxmox_VE_Cluster` | AppliesTo only. Its one instance still carries the cluster vertex key (§3.4); it includes v1's migration counts | 4 + 2 per online node (migration history) |
| `Proxmox_VE_Cluster_Nodes` | Cluster | `Proxmox_VE_Nodes` | **Keeps only `Status`** (online). A node that is down cannot report itself down, so this stays on the cluster role; CPU, memory and uptime move to `Proxmox_VE_Node_Detail` | O(1) |
| `Proxmox_VE_Cluster_GuestStatus` | Cluster | `Proxmox_VE_GuestStatus` | AppliesTo only. Instance ERIs (guest MACs) unchanged | O(1), plus the hourly guest-config read in discovery |
| `Proxmox_VE_Cluster_GuestPerformance` | Cluster | `Proxmox_VE_GuestPerformance` | AppliesTo only | O(1) |
| `Proxmox_VE_Cluster_StorageCapacity` | Cluster | `Proxmox_VE_StorageCapacity` | AppliesTo only | O(1) |
| `Proxmox_VE_Cluster_Ceph` | Cluster | `Proxmox_VE_Ceph` | AppliesTo only | O(1) |
| `Proxmox_VE_Cluster_CephOSD` | Cluster | `Proxmox_VE_CephOSD` | AppliesTo only. Any node returns the whole CRUSH tree | O(1) |
| `Proxmox_VE_Cluster_Backups` | Cluster | `Proxmox_VE_Backups` | AppliesTo only. Keeps its loop over nodes for vzdump tasks: the view is cluster-wide | O(jobs + nodes) |
| `Proxmox_VE_Cluster_Snapshots` | Cluster | `Proxmox_VE_Snapshots` | AppliesTo only. Hourly, with v1's per-node circuit breaker | 2 + 1 per guest on an online node, hourly |
| `Proxmox_VE_Cluster_Topology` | Cluster | `Proxmox_VE_Topology` | AppliesTo only (§3.4) | O(1), plus the hourly guest-config read |
| `Proxmox_VE_Node_Detail` | Node | `Proxmox_VE_NodeDetail` | Single-instance; asks its own node. **Gains `CPUUsagePercent`, `CPUCount`, the memory trio and `UpTimeSeconds`** from the same `/nodes/{node}/status` call, so a node's performance alerts are raised on the node (the reason for this section) and each datapoint still lives in one module, as v1 DESIGN §4 requires | O(1) per node |
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

- **Node vertices:** each node resource's own ERI, using v1's name-plus-IP key
  (`pveTopoNodeVertexKey`) and still exactly one key per vertex (v1 DESIGN §7, 2026-10-21).
- **Guest vertices:** the MAC, as in v1.
- **Cluster vertex:** unchanged from v1: `Proxmox_VE_Cluster_Status`'s one instance carries
  `proxmoxve--<cluster>`, type `Cluster`, and the map draws it with Cluster edges to every node
  (v1 spike 3 re-run, 2026-10-22). This holds whichever resource carries the cluster role, so Q8
  does not affect the map, and no cluster ERI PropertySource is needed.

### 3.5 Upgrade from v1

Moving from instances to resources does not convert anything, and LogicMonitor says the same of
its own VMware migration. The rules:

- **v2 needs no v1** (user, 2026-10-25): a fresh install is v2 alone, and nothing below applies.
- Run v1 and v2 side by side only briefly, because both poll the same API. The new names (§3.2)
  keep the two from replacing each other on import. **Categories alone would not keep v1 off v2's
  resources:** v1's `addCategory_Proxmox_VE` applies on `true()` and tags any resource holding
  `pve.api.token.credential` with `ProxmoxVE`, which applies all of v1 [v1, `pve_common.groovy`
  62–69; found 2026-10-25]. v2's token is therefore **`pve.token.credential`** (user, 2026-10-25),
  which v1 never reads, so v1 does not apply to a resource v2 created. A v1 node v2 adopts (NetScan
  spec §6) still holds v1's property and v1's modules until that property is deleted, which ends
  the overlap for that node.
- While both run, the cluster key `proxmoxve--<cluster>` sits on two instances (v1's
  `Proxmox_VE_Cluster` and v2's `Proxmox_VE_Cluster_Status`), and v1's `addERI_Proxmox_VE` and
  v2's `addERI_Proxmox_VE_Node` stamp the same node key. Same keys, so the node ERIs merge
  harmlessly; the doubled cluster key is a multi-match whose drawing is untested, which is one
  more reason to keep the overlap short.
- Then delete v1's token property from the adopted nodes and disable v1's modules. v1 instance
  history stays readable until v1 is deleted. No module is shared, so removing v1 removes nothing
  v2 uses.

The v1 guest wildvalues carry over unchanged to the cluster resource. History does not, because
the instances live on a different resource. This is stated once, in the install guide.

### 3.6 When a guest should be a resource

The user's position (2026-10-21): a guest needs to be a resource only for what can be seen from
inside it — an application, a service, a process, events or logs. Checked against LogicMonitor's
documentation:

| Need | An instance on the cluster resource | A guest resource | Source |
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
the instance, with no in-guest access needed. Its cost — 1 HU, and a hostname that may not answer
ping — is the user's choice, per guest or per filter.

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
- **The dedicated cluster resource of §3.1 is one new HU per cluster** that v1 does not spend:
  v1's token-holding node doubles as the cluster. The alternative is to put the cluster role on a
  node resource, as v1 does, and fail over through `pve.api.hosts` from there. That costs 0 HU.
  The catch: the cluster-level instances then live on a node resource, and leave with it if that
  node is retired. **Chosen: the dedicated resource** (Q8, user 2026-10-25), so v2 is the right-hand
  column below.

#### v1 against v2, in host units (2026-10-25)

From the table above: 1 HU per resource, 0 per instance. *N* is the nodes in a cluster, *G* its
guests, *S* the guests selected for guest resources, and *A* the guests that are already resources
through SNMP, WMI or SSH. Those *A* cost 1 HU each under both versions alike, and v2's
deduplication adopts them, so selecting them adds nothing; the rows below take them to be among
the selected.

| Deployment | v1 | v2, cluster role on a node (Q8 `node`) | v2, dedicated cluster resource (Q8 `dedicated`) |
|---|---|---|---|
| Standalone host | 1 | 1 | 1 (a standalone host never has a dedicated resource) |
| Cheapest possible, per cluster | **1**: the token holder alone collects everything, node-level modules included, by fan-out; only the other nodes are missing from the map | **1** (`pve.netscan.nodes=false`): cluster-level data and each node's online state only. **No node-level modules** (disks, certificates, services, detail), since they apply only to node resources | 2 |
| As documented (every node a resource) | *N* (v1 INSTALL §3.3, for the map) | *N* | *N* + 1 |
| + guests as resources | *N* + *A* (v1 creates none; *A* are the user's own) | *N* + *S* | *N* + 1 + *S* |
| Lab: 2 nodes, 9 guests, guests off, none SNMP | 2 | 2 | 3 |
| Target: 12 nodes, 500 guests, guests off, none SNMP | 12 | 12 | 13 |
| Target, 10 guests already SNMP, 50 selected (those 10 among them) | 12 + 10 = 22 | 12 + 50 = 62 | 63 |
| Target, 10 guests already SNMP, every guest selected | 22 | 12 + 500 = 512 | 513 |

**At its defaults v2 costs exactly what v1 costs as documented**, or one more per cluster with a
dedicated cluster resource. The licence cost of v2 is **opt-in**, and is entirely guests: *S*
minus *A*, the guests already monitored.

**At the scale target (Q6: 10 clusters of 16 nodes and 1,000 guests):** 160 HU under v1 as
documented and **170 under v2** with guests off — ten more in all, one per cluster resource. Every
guest selected adds up to 10,000 HU, less the guests already monitored: 10,170 with every guest.

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

**Everything is copied into this repository** from v1 commit `2e0237b` (v1 complete), the commit
recorded here, as the skill pins its fixtures: all 19 modules' scripts and definitions as the
starting point of §3.2's rows, and the pieces below unchanged except where stated (user,
2026-10-25: v2 is standalone and customises its own copy). The copy is the implementation plan's
first task, verbatim, so every later change shows as a diff against v1. That includes what v1 added after `e6873b7`: graphs on every
DataSource and the build's `check_graphs`, `pveSizeLabel` sizes in instance names and descriptions,
and the rule that end-user text points at Proxmox's own views because stderr on a successful run is
not shown (v1 DESIGN §7). v1 keeps its own history; a later v1 fix reaches v2 only by copying it over.

- `scripts/lib/pve_common.groovy`: properties, TLS, HTTP, `pveWildValue`, the emit and discovery
  helpers, the topology key and guest MAC helpers. The token is read from `pve.token.credential`
  (§3.5); the other `pve.api.*` names stay, since v1 keys on none of them. One addition: failover across `pve.api.hosts`,
  used by the cluster role only (§3.2); per-node requests addressed to the owning node, bounded
  concurrency and a time budget for the O(guests) loops (§3.9).
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
| Cluster resource | ~2,550 (with the shared config read below; ~3,550 without) | ~2,000: one guest-config read (GuestStatus discovery and Topology together) and one snapshot read per guest |
| Each node resource | ~31, to itself | 0 |
| 16 nodes | ~490 | 0 |
| NetScan, guests off | 18 | 0 (up to ~2,000 with every guest selected) |
| **One cluster** | **~3,050, 0.85 a second** | |
| **Ten clusters** | **~30,500**, spread over their Collectors | |

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
   `lm.cache`. That is designed, and built only if measured.

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

The spikes in §5 answer Q2–Q4, run together with the user. The user's questions, Q6–Q9, are
answered (2026-10-25).

- **Q1. Answered** 2026-10-25 (§3.2): VMware's VM modules apply to the vCenter resource; per-host
  VM modules exist only for standalone ESXi.
- **Q2.** Can a Proxmox NetScan, built from the Collector snippets above, create a cluster, a node
  and a guest resource in a test group, with groups and properties as in §3.3?
- **Q3.** Does `lm.api` deduplication adopt an existing SNMP or WMI guest resource, or only report
  it? And can MAC matching be added?
- **Q4.** Does `lm.cache` share data between a TopologySource and a DataSource's discovery on one
  Collector? Needed at the scale target for the single guest-config read (§3.2).
- **Q5. Licensing:** answered in §3.7. `pve.netscan.guests` defaults to off.
- **Q6. Scale target: answered** — 10 clusters of 16 nodes and 1,000 guests each (§3.7, §3.9).
- **Q7. Where the token lives: answered** — one Proxmox API token per cluster (user, confirmed
  2026-10-25), held by that cluster's NetScan and written onto its cluster and node resources
  (NetScan spec §7).
- **Q8. The cluster role: answered** — a separate cluster resource (§3.1, §3.7).
- **Q9. Node hostnames: answered** — the node's `/cluster/status` address by default, the name
  as an option (NetScan spec §5.2).

## 5. Spikes, before any plan

| Spike | Answers | Method | Cost |
|---|---|---|---|
| S1 | Q1 | read the VMware VM modules' AppliesTo | **done** 2026-10-25, from LogicMonitor's exports |
| S2 | Q2 | a throwaway NetScan against the lab cluster that creates resources in a test group; detail in NetScan spec §10 | one import, one run, then delete the group |
| S3 | Q3 | the same NetScan run against the v1 node resources and a guest already monitored over SNMP | the same run |
| S5, S6 | NetScan re-runs; guest-address privileges (S6 done 2026-10-25: `PVEAuditor` suffices on 9.2.3) | NetScan spec §10 | two runs; API calls from a workstation |
| S7 | Q4 | a throwaway TopologySource and DataSource on one Collector, one writing an `lm.cache` key and the other reading it | one import each, then deleted |
| S4 | failover; `/nodes/localhost` | the preamble with two addresses, the first unreachable; and one `GET /nodes/localhost/status` over HTTPS | harness plus one Collector debug run |

## 6. Not in scope

- Per-guest Proxmox API modules on guest resources. Phase 1 is guests as resources for
  organisation and in-guest monitoring.
- LogSources, ConfigSources and the troubleshooter module. They are good later additions; VMware
  ships all three.
- Deleting resources from the NetScan.
