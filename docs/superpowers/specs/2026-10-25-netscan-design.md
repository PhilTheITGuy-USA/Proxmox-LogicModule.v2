# Proxmox VE suite v2: the NetScan

Status: **draft for review**, 2026-10-25. Part of the v2 architecture spec
(`2026-10-21-proxmox-v2-architecture-design.md`, §3.3), which it details. Nothing here is built.
Built on v1 complete at `2e0237b` and on the vSphere NetScan as summarised in
`docs/research/2026-10-21-lm-vsphere-architecture.md` §2.

Each claim about LogicMonitor or Proxmox is tagged by how it is known:

- **[v1]** read in v1's code or proven in v1 DESIGN §7;
- **[vmware]** what the vSphere NetScan does (research §2);
- **[unverified]** believed, not yet seen; the spike named beside it settles it.

## 1. What it is for

One scheduled job per Proxmox cluster (or standalone host) that creates and keeps current the
resources of the three roles of the main spec §3.1, so that nobody sets a category, a token or a
topology property by hand. It replaces everything v1 asked of a user on the LogicMonitor side
(v1 INSTALL Part 4 and §3.3): the token on one node, `pve.topology.node` on a node without SNMP,
`pve.topology.cluster` on a group, and the knowledge of which node holds the token.

It **does not collect metrics.** Everything it learns is written as resource properties and
groups; the DataSources do the monitoring.

## 2. Inputs (NetScan properties)

Read through the NetScan's property binding [unverified, S2], under the same names the resources
use where the value is copied onto them.

| Property | Default | Meaning |
|---|---|---|
| `pve.api.url` | — (required) | `https://<address>:8006` of any node, a VIP, or a DNS round-robin name. Only the NetScan and the cluster role use it |
| `pve.api.token.credential` | — (required) | the whole `user@realm!tokenid=secret`, as v1. `PVEAuditor` on `/` suffices for everything except §5.3's guest-agent address |
| `pve.api.insecure`, `pve.api.timeout` | `false`, `10000` | as v1; copied onto every resource the NetScan writes the token to |
| `pve.netscan.rootFolder` | `Proxmox VE` | the root group of §4 |
| `pve.netscan.nodes` | `true` | create node resources. `false` leaves only the cluster role (§3.1) |
| `pve.netscan.node.hostname` | `ip` | a node resource's hostname: `ip` (its `/cluster/status` address) or `name` (its node name, which must resolve from the Collector) |
| `pve.netscan.guests` | `false` | create guest resources. Off by default: each is 1 HU (main spec §3.7) |
| `pve.netscan.guests.filter.pool`, `.tag`, `.node`, `.vmid`, `.type`, `.status` | none | comma-separated allow-lists; a guest must pass every one that is set. `.vmid` takes ranges (`100-199`), `.type` is `qemu` / `lxc`, `.status` is `running` / `stopped` |
| `pve.netscan.guests.groupBy` | `pool` | `pool` groups guests under `Guests/Pool - <pool>`; `none` keeps them flat. Never by node (§4) |
| `pve.netscan.cluster.role` | per Q8 | `node` (a node resource also carries the cluster role) or `dedicated` (a resource of its own). §3.1 |
| `lmaccess.id`, `lmaccess.key` | none | LogicMonitor API credentials for §6's deduplication, as VMware names them [vmware]. Without them there is no deduplication, and the run says so |
| `pve.netscan.dedupe` | `true` when the LM credentials are set | `false` turns §6 off, as VMware's `skip.device.dedupe` |

**Filters are properties the script reads, not the NetScan UI's filter rows,** so the harness can
test them. Whether the UI's own filters also apply to an Enhanced Script NetScan's output is
[unverified, S2]; if they do, the documentation says to use one mechanism, not both.

## 3. What one run does

All Proxmox reads go through the v1 preamble's `pveGet` against `pve.api.url`.

1. **`GET /cluster/status`** [v1]. The `cluster` row gives the cluster name; its absence means a
   standalone host, named after its single node row. Each `node` row gives `name`, `nodeid`,
   `ip`, `online` [v1 fixture]. One call.
2. **`GET /cluster/resources`** [v1]. Nodes' `status` and `level`, and every guest's `vmid`,
   `name`, `node`, `type`, `status`, `pool`, `tags`, `template`. One call.
3. **Node reachability:** for each node, `GET /nodes/<name>/status` **sent to that node's own
   hostname** (§3.2), with the token. This is the one thing the node modules will need that the
   API address cannot prove: that the Collector reaches the node directly. O(nodes), like
   VMware's per-host login [vmware]. Skipped for an offline node.
4. **Guests, only if `pve.netscan.guests`:** filter (§2), drop templates [v1], then for each
   remaining guest read its `config` (MAC, static address) and, where §5.3 says so, its live
   address. O(selected guests).
5. **Deduplicate** each resource against the portal (§6).
6. **Emit** every resource through `lm.emit` [unverified shape, S2].

So a run costs 2 + O(nodes) + up to 2 per selected guest. Hourly, as VMware allows [vmware], a
12-node cluster with guests off is 14 calls an hour; with 500 guests selected, about 1,014.

## 4. Groups

```
<rootFolder>/
  Cluster - <cluster>/          the dedicated cluster resource, if any (§3.1)
    Nodes/                      every node resource, the cluster-role node included
    Guests/                     only if pve.netscan.guests
      Pool - <pool>/            with groupBy=pool; guests in no pool stay in Guests/
  Host - <node>/                a standalone host instead of Cluster - …
    Guests/
```

One NetScan per cluster, as VMware runs one per vCenter [vmware]; several write side by side
under one `rootFolder`. **Guests are never grouped by node,** because a migration would move the
resource every time; the node is the `pve.node` property instead, and a per-node view is one
dynamic group on it [vmware]. Proxmox tags become `pve.tags` for dynamic groups too.

## 5. The resources it writes

### 5.1 The cluster role

| Q8 answer | Resource | Hostname | Display name | Categories |
|---|---|---|---|---|
| `node` | the node with the **lowest `nodeid`**, or the one named by `pve.netscan.cluster.node` | its node hostname (§5.2) | its node name | `ProxmoxVE_Cluster,ProxmoxVE_Node` |
| `dedicated` | a resource of its own | the host part of `pve.api.url` | `<cluster>` | `ProxmoxVE_Cluster` |
| standalone host | the one node | as §5.2 | its node name | `ProxmoxVE_Cluster,ProxmoxVE_Node` |

Properties, beside the token set of §2: `pve.cluster` = the cluster name, `pve.api.hosts` = every
node's `/cluster/status` address, ordered by `nodeid` with the API address's node first, and
`pve.serial` = `<cluster>`. The cluster modules fail over through `pve.api.hosts`.

**The role is sticky by construction.** The lowest `nodeid` changes only when that node leaves
the cluster, and moving the role moves the cluster-level instances (their history stays on the
old resource), so the role is chosen by a rule that does not follow node state. A down
role-carrying node does not move it: the cluster modules fail over instead.

**A dedicated resource without a VIP** has the same hostname as one node. Whether the portal and
the NetScan accept two resources with one hostname on one Collector is [unverified, S2]; if not,
`dedicated` requires a VIP or a DNS name of its own, and the documentation says so.

### 5.2 Nodes

One resource per `/cluster/status` node row, offline ones included (a node that is down still
needs its place on the map and its down state):

- **Hostname:** `pve.netscan.node.hostname`. The default, `ip`, is the address the topology key
  is built from (`proxmoxve--node--<name>--<ip>`, [v1]), so `addERI_Proxmox_VE_Node` matches
  without SNMP. It is the corosync link address, which on a dedicated cluster network may not be
  reachable from the Collector; step 3 then fails and says so, and `name` is the fix.
- **Display name:** the node name.
- **Categories:** `ProxmoxVE_Node`, always: it is what the resource *is*, and the ERI and topology
  need nothing else.
- **Properties:** the token set of §2, `pve.cluster`, `pve.node` = the node name (so no module
  has to infer it, and `pveTopoNodeName` has its answer [v1]), `pve.serial` =
  `<cluster>::node::<name>`, and **`pve.netscan.reachable`** = `true` / `false` from step 3, with
  `pve.netscan.status` carrying the reason when `false`.
- **The node API modules apply only where `pve.netscan.reachable` is not `false`.** A node the
  Collector cannot reach keeps its resource, its ERI and its place on the map, and raises no
  collection errors; the next run that reaches it switches them on. This is VMware's "added
  without the category, with `esx.netscan.status`" [vmware], kept narrower so the category itself
  never changes. It depends on a re-run rewriting a property it already wrote [unverified, S5].

### 5.3 Guests (opt-in)

- **Selected** by §2's filters; templates never.
- **Hostname,** first that exists — Proxmox knows a guest's address only from inside it:
  1. its live address: for a container, `GET /nodes/<n>/lxc/<vmid>/interfaces`; for a VM with
     `agent: 1` in its config and running, the guest agent's `network-get-interfaces`. The first
     non-loopback, non-link-local IPv4, else IPv6. Which privilege the agent call needs on 9.2,
     and whether `PVEAuditor` has it, is [unverified, S6]; without it this step is skipped;
  2. a static address in the config (a container's `ip=` on `net0`, a VM's cloud-init
     `ipconfig0`);
  3. the guest's name, as VMware falls back to the display name [vmware].
- **Display name:** the guest name; if two selected guests share one, ` (<vmid>)` is appended to
  both, every run, so the pair's names do not depend on which was seen first.
- **Categories:** `ProxmoxVE_Guest`.
- **Properties:** `pve.cluster`, `pve.node`, `pve.vmid`, `pve.guest.type`, `pve.pool`, `pve.tags`
  (Proxmox's own `;`-list), `pve.guest.mac` (first NIC's MAC, as `pveGuestMac` reads it [v1]),
  `netscan.powerstate` [vmware], `pve.serial` = `<cluster>::<vmid>`. **Never the token:** no guest
  module calls the Proxmox API (main spec §3.2).
- `addERI_Proxmox_VE_Guest` stamps `pve.guest.mac`, so the map's guest vertex opens the guest
  resource instead of the cluster role's instance (main spec §3.6).

## 6. Deduplication

**Why it matters here more than for VMware:** v1 users already have every node as a resource
(v1 INSTALL §3.3), and often guests over SNMP or WMI. A NetScan that duplicated them would double
their host units.

With `lmaccess.id` / `lmaccess.key`, before emitting each resource the run asks the portal through
`lm.api` [vmware] for an existing device, in this order:

1. same hostname;
2. same display name;
3. guests only: `auto.network.mac_address` equal to the guest's MAC — Proxmox's addition, since a
   guest found over SNMP is often named and addressed differently from its Proxmox name.

**On a match the existing device is adopted:** the resource is emitted with that device's own
hostname and display name, so the portal treats it as the same device, and the NetScan's
properties and group are added to it [vmware, `hostname.source=lm`]. Its `system.categories` are
emitted as the union of the device's existing categories and the role's, so an adopted v1 node
keeps `ProxmoxVE` and anything else it had. Whether adoption works on a device the NetScan did not
create, whether the portal merges or replaces `system.categories`, and whether `lm.api` can filter
on an auto property for step 3 are all [unverified, S3].

Without the credentials there is no lookup; the run's output states it, and the install guide
says to set them before the first run on a portal that already has Proxmox resources.

## 7. Lifecycle

- **Adds and moves, never deletes** [vmware]. A guest removed from Proxmox, or a node removed from
  the cluster, keeps its resource.
- **Reporting the orphans:** with the LM credentials, the run lists devices carrying this
  cluster's `pve.cluster` and a Proxmox category whose `pve.serial` it did not emit this run, and
  writes them to its output for an operator to retire. Without the credentials this is not
  possible and is not attempted.
- **Properties stay current only if a re-run rewrites them:** `pve.node` after a migration,
  `pve.api.hosts` after a node joins, the token after a rotation, `pve.netscan.reachable`.
  VMware's "add or move" implies it [vmware]; S5 proves it, including what happens to a property a
  later run no longer emits.
- **Token rotation:** change the NetScan's `pve.api.token.credential` and re-run. A token set on a
  group instead (Q7) would be overridden by the resource-level one the NetScan writes, so the two
  are never mixed.

## 8. Failure behaviour

- **The API address answers nothing, or rejects the token:** the run emits nothing and fails with
  v1's 401/403 wording [v1]. Nothing is deleted, so no resource is harmed.
- **An empty `/cluster/resources` with a valid token** is v1's under-privileged case [v1]: the
  run emits the cluster role and nodes from `/cluster/status`, no guests, and says why.
- **One node unreachable:** §5.2 — the node is still written, marked unreachable.
- **One guest's config or address unreadable:** that guest falls through §5.3's hostname order;
  a per-node circuit breaker stops trying a node's guests after the first failure, as v1's
  Snapshots does [v1].
- **`lm.api` unavailable mid-run:** deduplication is skipped for the rest of the run, which then
  emits only resources already proven absent, and says so — never a resource that might
  duplicate.

## 9. Code layout and testing

v1's split [v1, DESIGN §6 item 9]: everything Proxmox-specific is plain Groovy the harness can run,
and only the snippet calls are Collector-only.

- **`pveNetscanPlan(status, resources, configs, addresses, props, existing)`** in the preamble — a
  pure function returning the list of resources (hostname, display name, categories, properties,
  group) and the output lines. The harness drives it from the mock API with each `props` case:
  standalone, two clusters' names, offline node, unreachable node, both Q8 answers, every filter,
  duplicate guest names, every hostname fallback, each dedupe match and miss.
- **`scripts/Proxmox_VE_NetScan.groovy`** — the thin body: read the NetScan properties, make the
  reads of §3, call `lm.api` and `lm.emit`. Built to `dist/netscan/`, outside `groovyc` and the
  harness, as v1 does for its Collector-only scripts [v1].
- **A build cross-check,** in the spirit of v1's dashboard check: every category and property the
  plan writes that an AppliesTo reads (`ProxmoxVE_Cluster`, `ProxmoxVE_Node`, `ProxmoxVE_Guest`,
  `pve.netscan.reachable`, `pve.api.hosts`) must match the module definitions, so renaming one
  side fails the build instead of silently stopping the suite applying.
- **`lm_lint_module.py` does not cover a NetScan;** it is not a LogicModule. The skill gets a note
  and, once S2 has shown the real output shape, a check.

**Installing it:** Resources → Add → Advanced NetScan → Enhanced Script NetScan, paste the
built script, set §2's properties, schedule hourly. Creating it through the REST API is possible
[unverified] but not needed for v2.

## 10. Spikes

S2 and S3 are the main spec's; S5 and S6 are added here.

| Spike | Settles | Method |
|---|---|---|
| S2 | the property binding, the `lm.emit` output shape, whether UI filters apply, two resources with one hostname | a throwaway NetScan on the lab writing a cluster, two nodes and one guest into a test group, then the group deleted. **Needs the vSphere NetScan script** as the shape reference (skill rule 8) |
| S3 | adopting a device the NetScan did not create; `system.categories` merge or replace; `lm.api` filtering on `auto.network.mac_address` | the same NetScan against the existing v1 node resources and an SNMP guest |
| S5 | a re-run rewriting properties and moving groups; what happens to a property no longer emitted | run twice, changing `pve.node` (migrate a guest) and a NetScan property between runs |
| S6 | the guest-agent privilege on 9.2.3; `/lxc/<vmid>/interfaces` on 9.2.3 | two `curl`s with the `PVEAuditor` token |

## 11. Open questions for the user

- **Q7** (main spec): the token on the resources (this design, as VMware) or on a group.
- **Q8** (main spec): `pve.netscan.cluster.role` — §5.1 supports either; the default is the answer.
- **Q9.** The node hostname default: `ip` (works with the map out of the box; fails on a separate
  corosync network) or `name` (needs DNS from the Collector).
