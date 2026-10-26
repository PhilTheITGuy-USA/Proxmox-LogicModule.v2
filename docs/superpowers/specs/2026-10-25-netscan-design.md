# Proxmox VE suite v2: the NetScan

Status: **draft for review**, 2026-10-25; revised the same day with the user's answers to Q6–Q9
and with what LogicMonitor's vSphere NetScan script shows (supplied by the user 2026-10-25,
summarised in research §2a, not copied); revised again after spike S2 (2026-10-25): the cluster
role is an active and a standby resource (§5.1), guest hostnames stay IPs (§5.3), deduplication
compares addresses (§6). Part of the v2 architecture spec
(`2026-10-21-proxmox-v2-architecture-design.md`, §3.3), which it details. Nothing here is built.
Built on v1 complete at `2e0237b`.

Each claim about LogicMonitor or Proxmox is tagged by how it is known:

- **[v1]** read in v1's code or proven in v1 DESIGN §7;
- **[vmware]** read in the vSphere NetScan script or LogicMonitor's vSphere page (research §2, §2a);
- **[unverified]** believed, not yet seen; the spike named beside it settles it;
- **[S<n>]** seen in the lab cluster `Labcluster` (PVE 9.2.3) by the spike named, dated in §10.

## 1. What it is for

One scheduled job per Proxmox cluster (or standalone host) that creates and keeps current the
resources of the three roles of the main spec §3.1, so that nobody sets a category, a token or a
topology property by hand. It replaces everything v1 asked of a user on the LogicMonitor side
(v1 INSTALL Part 4 and §3.3): the token on one node, `pve.topology.node` on a node without SNMP,
`pve.topology.cluster` on a group, and the knowledge of which node holds the token.

It **does not collect metrics.** Everything it learns is written as resource properties and
groups; the DataSources do the monitoring.

**Scale target (Q6, user 2026-10-25): 10 clusters of 16 nodes and 1,000 guests each.** §12 carries what that
changes.

## 2. Inputs (NetScan properties)

Read from the `netscanProps` binding; in the Collector's debug console, where that binding does not
exist, from `hostProps` instead, which is how the vSphere script makes itself runnable from a
resource [vmware]. This script does the same, so the spikes can run it from the debug console.

**Where they are entered:** the Advanced NetScan form has no "properties" section. They go under
**Resource Credentials Options → "Use custom credentials for this scan" → Add**, a key and value
each ([LM doc](https://www.logicmonitor.com/support/enhanced-script-netscan)); the script then
reads them through `netscanProps` [S2]. That screen masks a `.credential` value [S2, user
2026-10-25]. The same section offers "Inherit credentials from Device / Device Group"; whether
inherited properties reach `netscanProps`, which would let an install reuse a token already on a
resource, is [unverified]; S2 saw at least one property the user never entered (`netapp.ssl`)
arrive, so some properties from outside the NetScan do.

| Property | Default | Meaning |
|---|---|---|
| `pve.api.url` | — (required) | `https://<address>:8006` of any node, a VIP, or a DNS round-robin name |
| `pve.token.credential` | — (required) | **one token per cluster** (Q7, user 2026-10-25): the whole `user@realm!tokenid=secret`. `PVEAuditor` on `/` suffices on PVE 9, the guest-agent address included [S6]. **Not v1's `pve.api.token.credential`** (user 2026-10-25): v1's `addCategory_Proxmox_VE` tags any resource holding that exact property with `ProxmoxVE` and applies all of v1 to it [v1, `pve_common.groovy` 62–69], so a different name keeps v1 off v2's resources with no change to v1. Permanent once shipped |
| `pve.api.insecure`, `pve.api.timeout` | `false`, `10000` | as v1; copied with the token |
| `pve.api.concurrency` | `2` | requests in flight per node in an O(guests) loop (main spec §3.9 rule 4); copied with the token so the modules use the same bound |
| `pve.netscan.cluster.address` | the host part of `pve.api.url` | the cluster's own address (VIP, proxy, DNS name), when it is not the API address. It decides §5.1's case |
| `pve.netscan.rootFolder` | `Proxmox VE` | the root group of §4 |
| `pve.netscan.nodes` | `true` | create every node resource. `false` creates only the cluster role's resources (§5.1): the cluster resource and its standby node, or the two role nodes (user, 2026-10-25, spec review) |
| `pve.netscan.node.hostname` | **`ip`** (Q9, user 2026-10-25) | a node resource's hostname: `ip` (its `/cluster/status` address) or `name` (the node name, which must resolve from the Collector) |
| `pve.netscan.node.maxfailures` | `10` | node reachability checks (§3 step 3) that may fail in one run before the rest are skipped, as VMware's `esx.maxloginfailures` [vmware] |
| `pve.netscan.guests` | `false` | create guest resources. Off by default: each is 1 HU (main spec §3.7) |
| `pve.netscan.guests.filter.pool`, `.tag`, `.node`, `.vmid`, `.type`, `.status` | none | comma-separated allow-lists; a guest must pass every one that is set. `.vmid` takes ranges (`100-199`), `.type` is `qemu` / `lxc`, `.status` is `running` / `stopped` |
| `pve.netscan.guests.unaddressed` | `false` | `true` also creates selected guests the run found no address for, with their name as hostname; they do not answer ping, so LogicMonitor shows them dead (§5.3) (user, 2026-10-25, spec review) |
| `pve.netscan.guests.groupBy` | `pool` | `pool` groups guests under `Guests/Pool - <pool>`; `none` keeps them flat. Never by node (§4) |
| `skip.device.dedupe` | `false` | VMware's name and meaning [vmware]: `true` turns §6 off |
| `hostname.source` | empty | VMware's name and meaning [vmware], §6: empty skips and reports a collision, `lm` adopts the existing device, `netscan` renames the new one |
| `lmapi.timelimit.sec` | `60` | VMware's [vmware]: above this estimate, §6 queries the portal one device at a time |
| LogicMonitor API credentials | none | read by the `lm.api` snippet itself from the NetScan's properties [vmware]; their property names are LogicMonitor's (`lmaccess.id` / `lmaccess.key` in its documentation) [unverified]. They do not arrive unless entered [S2] |

**The LogicMonitor-side properties keep VMware's names** so that anyone who has run the vSphere
NetScan already knows them. **Filters are properties the script reads,** so the harness can test
them; whether the NetScan UI's own filter rows also apply is [unverified, S2].

## 3. What one run does

0. **Refuse a Collector older than 32.400**, as VMware does [vmware], with its version in the
   message.
1. **`GET /cluster/status`** [v1]. The `cluster` row gives the cluster name; its absence means a
   standalone host, named after its single node row. Each `node` row gives `name`, `nodeid`,
   `ip`, `online` [v1 fixture]. One call.
2. **`GET /cluster/resources`** [v1]. Nodes' `status`, and every guest's `vmid`, `name`, `node`,
   `type`, `status`, `pool`, `tags`, `template`. One call.
3. **Node reachability:** for each online node, `GET /nodes/<name>/status` **sent to that node's
   own hostname** (§5.2), with the token: the one thing the node modules need that the API
   address cannot prove. O(nodes), like VMware's per-host login [vmware], capped by
   `pve.netscan.node.maxfailures`. **And a ping** to the same hostname, and to the cluster address
   if there is one (user, 2026-10-25, spec review): a resource that does not answer ping is dead in LogicMonitor whatever
   its modules collect (main spec §3.1). A failure is written to `pve.netscan.status` and the run's
   output; it does not stop the resource being written. The ping is `InetAddress.isReachable`, a
   real ICMP echo only with the privilege for one [inferred], so it is a hint, not proof.
4. **Guests, only if `pve.netscan.guests`:** filter (§2), drop templates [v1], then for each
   remaining guest read its `config` (MAC, static address) and, where §5.3 says so, its live
   address. O(selected guests).
5. **The cluster role (§5.1):** decide the case from the cluster address and take the pair by
   `nodeid`. No API call.
6. **Deduplicate** each resource against the portal (§6).
7. **Emit** the list through `lm.emit`'s `resource` call [vmware]. Each entry is a map of
   `hostname`, `displayname`, `hostProps` (a map, `system.categories` among them) and `groupName`
   (a list of group paths, so one resource can sit in several groups) [vmware].

A run costs 2 + O(nodes) + up to 2 per selected guest. With guests off, a 16-node cluster is 18
calls an hour; with all 1,000 guests selected, about 2,018 (§12).

## 4. Groups

```
<rootFolder>/
  Cluster - <cluster>/          the cluster resource, where the cluster has an address (§5.1)
    Nodes/                      every node resource, the role nodes among them
    Guests/                     only if pve.netscan.guests
      Pool - <pool>/            with groupBy=pool; guests in no pool stay in Guests/
  Host - <node>/                a standalone host instead of Cluster - …
    Guests/
```

One NetScan per cluster, as VMware runs one per vCenter [vmware]; the ten of Q6 write side by side
under one `rootFolder`. **Guests are never grouped by node,** because a migration would move the
resource every time; VMware likewise groups VMs by cluster, pool and folder, never by host
[vmware]. The node is the `pve.node` property instead, and a per-node view is one dynamic group on
it. Proxmox tags become `pve.tags` for dynamic groups too.

## 5. The resources it writes

### 5.1 The cluster role: an active and a standby resource (user, 2026-10-25)

Main spec §3.1 gives the rule and why: a cluster resource without an address of its own is dead,
and a dead resource suppresses its alerts [S2, 2026-10-25]. The NetScan decides which case a
cluster is in, from the cluster address: `pve.netscan.cluster.address` if set (§2), else the host
part of `pve.api.url`. If that is neither a node's name nor a node's address, it is the cluster's
own address (a VIP, a proxy, a DNS name or a round-robin name).

| Case | Active | Standby |
|---|---|---|
| a cluster address | a **cluster resource**: hostname = the address, display name `<cluster>`, categories `ProxmoxVE_Cluster` | the node with the lowest `nodeid` |
| no cluster address | the node with the lowest `nodeid` | the node with the next `nodeid` |
| a standalone host | its one resource, `ProxmoxVE_Cluster,ProxmoxVE_Node` (main spec §3.1) | none |
| a one-node cluster, no address | that node | none, until a second node joins |

- **A node carrying the role** keeps its node hostname and display name (§5.2) and gets
  `ProxmoxVE_Node,ProxmoxVE_Cluster`.
- **Properties on both role resources,** beside the token set of §2: `pve.cluster` = the cluster
  name, `pve.api.url` as given, `pve.api.hosts` = every node's `/cluster/status` address ordered
  by `nodeid`, **`pve.cluster.role`** = `active` / `standby`, and on the standby
  **`pve.cluster.active`** = what it stands behind: the active node's name, or `address:<host>`
  for a cluster resource. The cluster resource's `pve.serial` is `<cluster>`; a node keeps its
  own (§5.2).
- **The cluster modules never use a role resource's hostname;** they reach the API through
  `pve.api.url`, failing over through `pve.api.hosts`.
- **Stable by construction.** The pair is the lowest `nodeid`s whether those nodes are online or
  not, so every run picks the same pair without reading the portal, LogicMonitor API credentials
  or none: a node that is down is the standby's case, not a reason to move the role, which would
  move every cluster instance and its history. The pair changes only when one of its nodes leaves
  the cluster, or when a cluster address appears or goes, and the run then reports it. The
  install guide says how to move the role by hand (before retiring a role node, say).
- **An address that appears later** (a VIP added): the run creates the cluster resource as
  active and makes the old active node the standby; the old standby loses the role. Its category
  goes, so its cluster instances stop collecting; their history stays readable.
- **The run's output states the case,** e.g. "cluster address `pve-vip.example.com`: separate
  cluster resource, standby Node-A" or "no cluster address: active Node-A, standby
  Node-B". For round-robin DNS it adds the warning of main spec §3.1. A round-robin name is
  recognised by resolving to more than one node address.

### 5.2 Nodes

One resource per `/cluster/status` node row, offline ones included (a node that is down still
needs its place on the map and its down state):

- **Hostname:** `pve.netscan.node.hostname`, by default **the node's `/cluster/status` address**
  (Q9). That is the address the topology key is built from (`proxmoxve--node--<name>--<ip>`), so
  `addERI_Proxmox_VE_Node` matches it in `system.ips` without SNMP, the case v1 proved on
  `Node-B` [v1]. It is the corosync link address; where that sits on a cluster network the
  Collector cannot reach, step 3 says so and `name` is the alternative. With `name`, `system.ips`
  holds whatever the name resolves to, and the node vertex matches only if that is the corosync
  address; the install guide says so.
- **Display name:** the node name.
- **Categories:** `ProxmoxVE_Node`, always: it is what the resource *is*, and the ERI and topology
  need nothing else. The active and standby nodes of §5.1 add `ProxmoxVE_Cluster`.
- **Properties:** the token set of §2, `pve.cluster`, `pve.node` = the node name (so no module has
  to infer it, and `pveTopoNodeName` has its answer [v1]), `pve.serial` =
  `<cluster>::node::<name>`, and **`pve.netscan.reachable`** = `true` / `false` from step 3's API
  check, with `pve.netscan.status` carrying the reason when `false`, and a failed ping from step 3
  whatever `pve.netscan.reachable` says.
- **The node API modules apply only where `pve.netscan.reachable` is not `false`.** A node the
  Collector cannot reach keeps its resource, its ERI and its place on the map, and raises no
  collection errors; the next run that reaches it switches them on. VMware instead adds such a host
  *without* its category, with `esx.netscan.status` [vmware]; keeping the category keeps the map.
  It depends on a re-run rewriting a property it already wrote [unverified, S5].

### 5.3 Guests (opt-in)

- **Selected** by §2's filters; templates never.
- **Hostname,** first that exists — Proxmox knows a guest's address only from inside it:
  1. its live address: for a container, `GET /nodes/<n>/lxc/<vmid>/interfaces`; for a VM with
     `agent: 1` in its config and running, the guest agent's `network-get-interfaces`. Both
     answer `PVEAuditor` on 9.2.3, which grants `VM.GuestAgent.Audit` [S6]. **Take the address of
     the interface whose MAC is `net0`'s** (case-insensitive), not the first address: guests
     report Docker bridges (`172.17.0.1/16`…), VPN tunnels (Tailscale `100.x`, MAC
     `00:00:00:00:00:00`), loopback and link-local alongside the real one, often first [S6]. On
     that interface, the first IPv4 (container addresses come as CIDR, `192.0.2.60/24`: strip
     the prefix), else a non-link-local IPv6. **The IP stays the hostname:** VMware swaps it for
     its reverse-DNS name [vmware], but that defeated LogicMonitor's duplicate-IP exclusion in the
     lab (§6) and cost ~1.3 s per guest where lookups fail slowly — ~22 minutes at 1,000 guests,
     one at a time [S2, 2026-10-25]. A name is an opt-in for later, with both costs stated, not a
     phase-1 property. No interface matches `net0`'s MAC: skip this
     step rather than guess. PVE 8 has no `VM.GuestAgent.*`; there the agent call needed
     `VM.Monitor`, which `PVEAuditor` lacked [unverified, no PVE 8 lab] — so a 403 from it means
     "skip this step and report it", never a failed run;
  2. a static address in the config (a container's `ip=` on `net0`, a VM's cloud-init
     `ipconfig0`). Only containers have one in the lab; the VM has none, and a DHCP container has
     neither [S6];
  3. none: **the guest is not created** and the run reports it (user, 2026-10-25, spec review). Its name, VMware's fallback
     [vmware], does not answer ping, so the resource would be dead from the start, as
     `Migration-Test` was [S2]. `pve.netscan.guests.unaddressed=true` creates it anyway, with its
     name as hostname.
- **Display name:** the guest name; if two selected guests share one, ` (<vmid>)` is appended to
  both, every run, so the pair's names do not depend on which was seen first.
- **Categories:** `ProxmoxVE_Guest`.
- **Properties:** `pve.cluster`, `pve.node`, `pve.vmid`, `pve.guest.type`, `pve.pool`, `pve.tags`
  (Proxmox's own `;`-list), `pve.guest.mac` (first NIC's MAC, as `pveGuestMac` reads it [v1]),
  `netscan.powerstate` [vmware], `pve.serial` = `<cluster>::<vmid>`, as VMware's `vcenter.serial`
  is `<vCenter UUID>::<VM UUID>` [vmware]. **Never the token:** no guest module calls the Proxmox
  API (main spec §3.2).
- `addERI_Proxmox_VE_GuestResource` stamps `pve.guest.mac`, so the map's guest vertex opens the guest
  resource instead of the cluster resource's instance (main spec §3.6).

## 6. Deduplication

**Why it matters:** a portal often monitors Proxmox nodes and guests already, over SNMP, SSH or
WMI, before the suite is installed. A duplicate costs a host unit.

As VMware's, through the `lm.api` snippet [vmware]:

- **Portal size first.** The snippet estimates how long listing every device would take; under
  `lmapi.timelimit.sec` it lists them all once (name, display name, current Collector) and matches
  in memory, otherwise it queries the portal once per resource [vmware].
- **Match order:** display name, then hostname, then — nodes — the `/cluster/status` address,
  as VMware also tries a host's IP after its reverse-DNS name [vmware].
- **Guests' MAC as a fourth key** is this suite's addition, because a guest found over SNMP is
  often named and addressed differently from its Proxmox name. The snippet's lookups seen so far
  match names only [vmware]; whether the portal can be asked for a device by
  `auto.network.mac_address` is [unverified, S3]. If not, MAC matching is dropped and the install
  guide says so.
- **On a match**, by `hostname.source` [vmware]:
  - empty (the default): the resource is **not emitted**, and the collision is reported, with
    both sides' hostname and display name;
  - `lm`: **adopt**: emit with the existing device's hostname and display name, so the NetScan's
    properties and group land on it;
  - `netscan`: emit as new with the display name `<name> - <hostname>`, which creates a second
    device and a second host unit.
- **Compare addresses, not hostname strings.** LogicMonitor's own NetScan setting "Exclude
  duplicate IP addresses: Matching any Monitored Resources" (on by default) compares the emitted
  hostname as written: a dry run excluded the 7 entries whose IP hostname matched an existing
  resource, but proposed adding `automation01`, the reverse-DNS name of `192.0.2.44`, beside the
  existing `192.0.2.44` resource [S2, dry run 2026-10-25]. So this script's own match resolves
  both sides to addresses, and §5.3 no longer swaps an IP for its DNS name by default.
- **Adopting resources that already exist** (a node or guest monitored over SNMP) is
  `hostname.source=lm`. LogicMonitor's exclusion above skips a match; it does not update it, so
  adoption has to come from the script or from the NetScan's "Criteria for updating existing
  resources" (default "Resources discovered by this NetScan") [unverified, S3].
- **Adoption and `system.categories`:** VMware writes `system.categories` outright [vmware].
  Whether that replaces an adopted device's categories (dropping whatever its own modules set) or
  merges is [unverified, S3]. If it replaces, the run emits the union of the device's
  categories and the role's, which needs the device listing to carry its properties
  [unverified, S3].

Without LogicMonitor API credentials, or with `skip.device.dedupe`, there is no lookup and the
run's output says so.

## 7. Lifecycle

- **Adds and moves, never deletes** [vmware]. A guest removed from Proxmox, or a node removed from
  the cluster, keeps its resource.
- **Reporting the orphans:** with the LM credentials, the run lists devices carrying this
  cluster's `pve.cluster` and a Proxmox category whose `pve.serial` it did not emit this run, and
  reports them for an operator to retire.
- **Properties stay current only if a re-run rewrites them:** `pve.node` after a migration,
  `pve.api.hosts` after a node joins, the token after a rotation, `pve.netscan.reachable`. VMware's
  "add or move" implies it [vmware]; S5 proves it, including what happens to a property a later
  run no longer emits.
- **Token (Q7): one per cluster,** held by that cluster's NetScan and written onto its cluster and
  node resources, as VMware writes its credentials onto every host it adds [vmware]. Rotation is
  one edit: the NetScan's `pve.token.credential`, then a run. A token also set on a group is
  overridden by the resource-level one, so the two are never mixed.

## 8. Failure behaviour

- **The API address answers nothing, or rejects the token:** the run emits nothing and fails with
  v1's 401/403 wording [v1]. Nothing is deleted, so no resource is harmed.
- **An empty `/cluster/resources` with a valid token** is v1's under-privileged case [v1]: the
  run emits the cluster resource and nodes from `/cluster/status`, no guests, and says why.
- **A node unreachable:** §5.2: still written, marked unreachable. After
  `pve.netscan.node.maxfailures` failures the remaining checks are skipped and those nodes keep
  the value they had.
- **One guest's config or address unreadable:** that guest falls through §5.3's hostname order; a
  per-node circuit breaker stops trying a node's guests after the first failure, as v1's
  Snapshots does [v1].
- **`lm.api` unavailable mid-run:** deduplication cannot finish, so the run emits only the
  resources already checked, and says so — never one that might duplicate.

## 9. Code layout and testing

v1's split [v1, DESIGN §6 item 9]: everything Proxmox-specific is plain Groovy the harness can run,
and only the snippet calls are Collector-only.

- **`pveNetscanPlan(status, resources, configs, addresses, props, existing)`** in the preamble — a
  pure function returning the resource maps of §3 step 7 and the report lines. The harness drives
  it from the mock API with each `props` case: standalone, two clusters' names, offline node,
  unreachable node, the API address being a node, every filter, duplicate guest names, every
  hostname fallback, each dedupe match under each `hostname.source`.
- **`scripts/Proxmox_VE_NetScan.groovy`** — the thin body: read the properties, make the reads of
  §3, call `lm.api` and `lm.emit`. Built to `dist/netscan/`, outside `groovyc` and the harness, as
  v1 does for its Collector-only scripts [v1].
- **A build cross-check,** in the spirit of v1's dashboard check: every category and property the
  plan writes that an AppliesTo or the preamble's role check reads (`ProxmoxVE_Cluster`,
  `ProxmoxVE_Node`, `ProxmoxVE_Guest`, `pve.netscan.reachable`, `pve.api.hosts`,
  `pve.cluster.role`, `pve.cluster.active`) must match the module definitions and the preamble.
- **`lm_lint_module.py` does not cover a NetScan;** it is not a LogicModule. The skill's
  `suite-design.md` §11 now carries what S2 showed of the Enhanced Script NetScan; a lint check
  waits until the script exists to check.

**Installing it:** Settings → NetScans → Add → Add Advanced NetScan, Discovery Method *Enhanced
Script NetScan*, §2's properties as custom credentials, the built script embedded, schedule
hourly; one per cluster ([LM doc](https://www.logicmonitor.com/support/enhanced-script-netscan)).

## 10. Spikes, run together with the user

Each is a throwaway against the lab cluster `Labcluster`, with its output deleted afterwards. S6
and S2 are done; S3, S5 (and the architecture spec's S7) run before the plan; S8 needs the built
cluster modules, so it runs during implementation.

| Spike | Settles | Method |
|---|---|---|
| S2 | whether the UI filter rows apply; a cluster resource whose hostname does not resolve; the LM credential property names; per-call latency and run time with every guest selected; the NetScan's and each module type's script timeout (main spec §3.9 rule 5) | a throwaway NetScan writing the cluster, both nodes and one guest into a test group. **Done 2026-10-25, except the items still open at the end.** The script ran first in a Groovy 4 / JDK 17 container against the lab (18 calls, 0.9 s without reverse DNS), then in the portal as dry runs: properties entered as custom credentials, the token masked; every emitted entry listed, unresolvable hostnames (`Labcluster`, `Migration-Test`) included; LogicMonitor's duplicate-IP exclusion held back every entry whose IP matched an existing resource, both v1 nodes included (§6). The dry-run screen gives counts (Scanned / Found / Added), not per-device labels. The spike's resources carry no token, so v1 cannot apply to them. **The real run** (filter `104,106,107`, nodes on, reverse DNS off): "Scanned 6, Found 6, Added 3", as predicted: `Labcluster`, `Webapp01` (`192.0.2.200`) and `Migration-Test` created; `.44`, `.51`, `.211` excluded as duplicates. The run record fills in over a minute or two, duplicates first. The NetScan **creates the group path** (`Proxmox VE v2 spike/Cluster - Labcluster/Guests`). On the Collector: 20 calls in 700 ms; medians config 15 ms, container interfaces 36 ms, VM agent 189 ms, node status 11–42 ms, so ~50 ms per container and ~205 ms per VM in a row. `netscanProps` carried every custom property and also `netapp.ssl`, which the user did not enter, so properties from outside the NetScan reach it; no `lmaccess.*` arrived on its own. **`Labcluster` was dead,** and nothing revived it: a script DataSource (`S2_KeepAlive`), `PingMulti-` at 0% loss to both nodes, or a loopback hostname, which instead drew the Collector's and Hyper-V's modules (main spec §3.1). A resource deleted and re-added by the NetScan came back with its old properties until it was also purged from **Recently Deleted**. Still open: the UI filter rows, credential inheritance, timeouts |
| S8 | the standby of §5.1: takeover when the active resource is shut down; whether the standby's open alerts clear when it goes quiet; the map during the hour after a takeover (main spec §3.4) | the built cluster modules on the lab's two nodes; shut the active node down, raise an alert on the standby, start the active node again |
| S3 | adopting a device the NetScan did not create; `system.categories` merge or replace; a portal lookup by MAC | the same NetScan with `hostname.source=lm` against the existing v1 node resources and the SNMP guest `Debian-Test` |
| S5 | a re-run rewriting properties and moving groups; what happens to a property no longer emitted | run twice, migrating a guest and changing a NetScan property between runs |
| S6 | the guest-agent privilege on 9.2.3; `/lxc/<vmid>/interfaces` on 9.2.3 | **Done 2026-10-25.** v1's token (`monitor@pve!logicmonitor`, privsep 0, `PVEAuditor` on `/`), from a workstation against Node-B: `/cluster/status`, `/cluster/resources`, and for each of the 7 running guests its interfaces call and `/config` — all 200. Results in §5.3. 9.2.3's `PVEAuditor` = `Datastore.Audit, Mapping.Audit, Pool.Audit, SDN.Audit, Sys.Audit, VM.Audit, VM.GuestAgent.Audit` (`pveum role list`) |

## 11. Questions

All four of the user's are answered (2026-10-25): **Q6** 10 clusters of 16 nodes and 1,000 guests (§12),
**Q7** one Proxmox API token per cluster, confirmed (§7), **Q8** first a separate cluster
resource, then, after S2, an active and a standby resource (§5.1), **Q9** node hostnames by their `/cluster/status` address (§5.2). What remains is in §10's spikes.

## 12. At the scale of Q6

Ten NetScans, one per cluster, each with 16 nodes. With guests off a run is 18 Proxmox calls and
16 node checks; the cap of §2 keeps a cluster with many unreachable nodes from spending a timeout
on each.

With all 1,000 guests selected a run is about 2,000 Proxmox calls (config and address per guest).
Its guest loop follows the main spec §3.9's rules like every O(guests) loop: each guest's reads go
to the node it runs on, with a per-node circuit breaker, `pve.api.concurrency` in flight, and a
time budget below the NetScan's timeout. S2 measured ~50 ms per container and ~205 ms per VM,
one at a time [S2]: 1,000 VMs is ~3½ minutes in a row, or ~7 s at 2 in flight on each of 16
nodes if the guests are spread evenly [inferred]. If a cluster would still not finish well inside
the hour, guest addresses are
cached between runs through `lm.cache` (as the vSphere script caches its own lookups [vmware]) and
refreshed for a share of the guests each run; a guest not refreshed keeps the address the previous
run wrote, so nothing is lost.

Deduplication on a portal of ~10,000 guests and their nodes will usually take VMware's
one-query-per-resource path (§6), so `lmapi.timelimit.sec` and the number of guests selected
together set the run time. The modules' own costs at this scale are in the main spec §3.9.
