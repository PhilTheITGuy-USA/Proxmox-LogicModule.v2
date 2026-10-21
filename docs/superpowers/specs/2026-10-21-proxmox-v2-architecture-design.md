# Proxmox VE suite v2: cluster, node and guest resources

Status: **draft for review**, 2026-10-21. Nothing here is approved or built. Research behind it:
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
- **The cluster is never drawn on the map.** Nothing can carry its key without misdrawing it (v1
  DESIGN §7, spike 3).

## 3. Design

### 3.1 Three resource roles, mirroring vSphere's three module sets

| Role | Category | vSphere analogue | Created by |
|---|---|---|---|
| **Cluster** | `ProxmoxVE_Cluster` | vCenter | NetScan, or manually |
| **Node** | `ProxmoxVE_Node` | ESXi host | NetScan, or manually |
| **Guest** | `ProxmoxVE_Guest` | VMwareVM | NetScan only, optional (`discover.guests`), **off by default** |

**Licensing decides the defaults** (§3.7): every resource costs one host unit (HU); instances
cost none. So a guest becomes a resource only when the user asks for it.

**The cluster resource is not a node.** Its hostname is the address the user gives the NetScan:
a VIP, a DNS round-robin name or one node. It carries `pve.api.hosts`, every node's address from
`/cluster/status`, and the preamble fails over through that list. This removes the single point
of failure.

**A standalone host is one resource carrying both roles**, `ProxmoxVE_Cluster,ProxmoxVE_Node`.
This is the counterpart of vSphere's `standaloneEsxi` mode.

**Categories are set by whoever creates the resource,** the NetScan or a user adding one by hand,
never inferred later from whether a token works. The token is set explicitly where it is needed:
the NetScan writes it onto cluster and node resources, as VMware's NetScan writes `esx.pass`. So
there is no stale category to clean up.

### 3.2 Which module goes where

| Role | Modules | Calls per poll |
|---|---|---|
| Cluster | Cluster (quorum, HA), Ceph, CephOSD, BackupCoverage, Replication, StorageCapacity (shared storage), GuestStatus, GuestPerformance, Topology | O(1), as in v1 |
| Node | NodeDetail, Disks, NodeServices, Certificates, Subscription, node-local storage | each node asks only itself: O(1) per node, and the v1 fan-out disappears |
| Guest | none from the Proxmox API in phase 1. In-guest monitoring (SNMP, WMI, SSH) applies by hostname, as it does for any resource | 0 |

**Guest hypervisor metrics stay instances on the cluster resource,** which is what VMware does if
research §3 holds (Q1 checks it). Guest instance wildvalues stay `pveWildValue(id)`, so a migration
keeps a guest's history.

### 3.3 The NetScan

An Enhanced Script NetScan modelled on VMware's (research §2), using the same Collector snippets:
`lm.emit`, `lm.api`, `lm.cache` and `lm.debug`, plus this suite's HTTP preamble.

**Inputs:** the API address and the token; optionally `rootFolder`, `discover.nodes` and
`discover.guests`; filters on cluster, node, pool, tag and status; and LogicMonitor API
credentials for deduplication.

**Output:**
- **Groups:** `<rootFolder>/Cluster - <name>/` holding the cluster resource, `Nodes/` and `Guests/`.
  Guests can also be grouped by pool. As in VMware, guests are never grouped by node, because the
  node changes on every migration.
- **Guest properties:** `pve.cluster`, `pve.node`, `pve.vmid`, `pve.pool`, `pve.tags.*`,
  `pve.guest.type`, `netscan.powerstate` and `pve.serial` = `<cluster>::<vmid>`. A per-node view
  is one dynamic group on `pve.node`, current to within one NetScan run.
- **Deduplication:** like VMware's, an existing device with the same display name or hostname is
  adopted or reported, never duplicated. Proxmox adds the MAC as a third match key, because a
  guest's IP is known only through the QEMU guest agent.
- **Lifecycle:** it adds and moves resources but never deletes them, as in VMware. A guest removed
  from Proxmox is reported in the NetScan output for an operator to retire.

**Manual adds** set the category and the properties by hand, documented per role, as VMware
documents "Manually Assigning Properties to Resources".

### 3.4 Topology

- **Node vertices:** each node resource's own ERI, using v1's name-plus-IP key
  (`pveTopoNodeVertexKey`) and still exactly one key per vertex (v1 DESIGN §7, 2026-10-21).
- **Guest vertices:** the MAC, as in v1.
- **Cluster vertex:** the cluster resource's ERI `proxmoxve--<cluster>`, type `Cluster`. Spike 3
  showed this type renders with the cluster icon. On a real resource the vertex is drawn.

### 3.5 Upgrade from v1

Moving from instances to resources does not convert anything, and LogicMonitor says the same of
its own VMware migration. The rules:

- Run v1 and v2 side by side only briefly, because both poll the same API.
- Then disable v1's modules. v1 instance history stays readable until v1 is deleted.

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
| Drawn under its node on the topology map, on a cluster | **open**: it seems to be drawn only under the node that holds the instance (v1 DESIGN §7, spike 3); the Debian-Test check on 2026-10-21 decides this | yes, resource to resource | v1 portal checks |

So the user's position holds, with two exceptions:
- **Multi-tenant access control** (an MSP, or a team that owns some guests) needs guests as
  resources in groups.
- **The map,** if the open row resolves against instances.

**Guest resources stay opt-in and filtered,** as in VMware's NetScan. A guest that is already a
resource through SNMP, SSH or WMI is adopted by deduplication, not duplicated. If the map row
resolves against instances, a NetScan-created guest resource carries its MAC from the guest config
(`pve.guest.mac`), and an ERI PropertySource stamps it. That draws the guest under its node with no
in-guest access. Its cost — 1 HU, and a hostname that may not answer ping — is the user's choice,
per guest or per filter.

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
  node is retired. This trade-off is Q8.

### 3.8 Carried over from v1 unchanged

- The preamble (properties, TLS, HTTP), extended with failover across `pve.api.hosts`.
- `pveWildValue`, the build, the harness and its mock API, the dashboard validation and the lint
  run order.
- Every portal lesson recorded in v1 DESIGN §7 and in the skill.

## 4. Open questions

The spikes in §5 answer the first four. The last three are yours.

- **Q1.** Does `VMware_vSphere_VirtualMachinePerformance` apply to the vCenter resource or to each
  VM resource? It decides whether §3.2's "guest metrics as cluster instances" is LogicMonitor's own
  pattern. Read-only check: the module's AppliesTo in the Exchange.
- **Q2.** Can a Proxmox NetScan, built from the Collector snippets above, create a cluster, a node
  and a guest resource in a test group, with groups and properties as in §3.3?
- **Q3.** Does `lm.api` deduplication adopt an existing SNMP or WMI guest resource, or only report
  it? And can MAC matching be added?
- **Q4.** Does `lm.cache` share data across resources on one Collector? This matters only for a
  later per-guest module.
- **Q5. Licensing:** answered in §3.7. `discover.guests` defaults to off.
- **Q6. Scale target:** the largest cluster and guest count to design and test for.
- **Q7. Where the token lives:** the NetScan writes it on cluster and node resources only. Do you
  want it on a group instead?
- **Q8. The cluster role:** a dedicated cluster resource (1 HU, independent of any node) or a node
  resource that carries the cluster role (0 HU, with failover)? See §3.7.

## 5. Spikes, before any plan

| Spike | Answers | Method | Cost |
|---|---|---|---|
| S1 | Q1 | read the VMware VM modules' AppliesTo in the Exchange | minutes, read-only |
| S2 | Q2 | a throwaway NetScan against the lab cluster that creates resources in a test group | one import, one run, then delete the group |
| S3 | Q3 | the same NetScan run against a guest already monitored over SNMP | the same run |
| S4 | failover | the preamble with two addresses, the first unreachable | harness plus one Collector debug run |

## 6. Not in scope

- Per-guest Proxmox API modules on guest resources. Phase 1 is guests as resources for
  organisation and in-guest monitoring.
- LogSources, ConfigSources and the troubleshooter module. They are good later additions; VMware
  ships all three.
- Deleting resources from the NetScan.
