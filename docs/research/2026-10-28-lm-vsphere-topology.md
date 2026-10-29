# How LogicMonitor's vSphere (and Hyper-V) suites draw topology

Date: 2026-10-28. Read from the modules installed in the lab portal (LogicMonitor's own code:
summarised here, never copied). Claims are tagged **[script]** (read in the module's script),
**[doc]** (LogicMonitor documentation, linked), **[api]** (the Proxmox VE API schema) or
**[inferred]**.

Purpose: the user's direction (2026-10-28) is to model Proxmox topology on LogicMonitor's vSphere
suite -- storage relationships (types, pools) and host, VM and cluster networking -- so the map a
vSphere customer knows reads the same for Proxmox.

## 1. The vSphere TopologySources

All four apply to the vCenter or ESXi resource and run hourly (`collectInterval` 3600) [script].

| TopologySource | Edges it draws | Edge type |
|---|---|---|
| `VMware_vCenter_Cluster_Topology` | vCenter → cluster → host; vCenter → standalone host; vCenter HA peers | `Manage` (`VCHA Cluster` for HA peers) |
| `VMware_vSphere_VirtualMachine_Topology` | host → VM; VM → virtual disk → datastore | `Compute`; `Storage` |
| `VMware_vCenter_Datastore_Topology` | host → datastore, for every host that mounts it | `Storage` |
| `VMware_vSphere_Network_Topology` | host physical NIC (by MAC) → the switch its CDP or LLDP neighbour names | `Network` |

Notes [script]:

- **Edges name vertices by key, not by resource.** Each edge end is a list of keys run through
  `lm.topo`'s `eriPreProcessor`; a VM is its first NIC's MAC, falling back to
  `<vcenter>--<vm MOR>`.
- **The network source draws nothing virtual.** No vSwitch, distributed switch or port group
  vertex: only the physical uplink to the physical switch, from the hypervisor's CDP/LLDP view.
  `VMware_vCenter_HostVSwitch` builds a vSwitch key in its discovery but never stamps it.
- **A virtual disk is its own vertex** between the VM and the datastore.

## 2. What carries each vSphere vertex

A vertex is drawn only where it matches a resource or a DataSource instance carrying the same key
(this suite's own finding, v1 DESIGN §7). vSphere carries every vertex on instances of its
monitoring DataSources, set in Active Discovery as `predef.externalResourceID` and
`predef.externalResourceType` [script]:

| Vertex | Key | ERT | Carried by |
|---|---|---|---|
| Cluster | `<vcenter>--<cluster MOR>` | `HyperVisorCluster` | `VMware_vSphere_Clusters` instances (and `_HighAvailability`) |
| Host | the host's hardware UUID (plus `<vcenter>--<host MOR>`) | `HyperVisor` | `VMware_vSphere_HostStatus` / `VMware_vCenter_HostPerformance` instances; `addERI_ESXi` on an ESXi resource |
| VM | MAC(s); `<vcenter>--<vm MOR>` | `VirtualMachine` | `VMware_vSphere_VirtualMachineStatus` / `_Performance` instances |
| Virtual disk | `<vcenter>--<vm MOR>--<disk>` | `VirtualDisk` | `VMware_vSphere_VirtualMachineDiskCapacity` instances |
| Datastore | `Datastore--<uuid from the datastore URL>`: **one key per datastore, whatever hosts mount it** | `VMDatastore` | `VMware_vSphere_DatastoreStatus`, `_DatastoreUsage` and `_DatastoreThroughput`: **the same key on instances of three DataSources** |
| Datastore cluster | `<vcenter>--<wildvalue>` | `DatastoreCluster` | `VMware_vSphere_DatastoreClusters` instances (no TopologySource draws edges to it) |
| Host interface | `interface:<mac>` | `Interface` | `VMware_vCenter_HostInterfaces` instances |

So LogicMonitor itself puts one vertex key on several instances (the datastore on three
DataSources) and relies on the map drawing it once.

**`VMDatastore` takes no edges here (lab, 2026-10-28).** This suite's store vertices, carried on
`Proxmox_VE_Cluster_StorageCapacity` instances as `VMDatastore`, resolved to their instances yet
took none of the edges the TopologySource printed for them (its Test Script output listed them),
before and after the instances were deleted and rediscovered; pool vertices typed
`StorageAggregate` on the same kind of carrier took theirs. Retyped `StorageVolume`, with nothing
else changed, every store edge drew. The user's screenshots of a vSphere portal show LogicMonitor's
own datastore vertices as "??" [inferred: the same cause]. Where an ERT is LM-specific, prove it on
a portal before relying on it.

## 3. Hyper-V, for virtual networking

`HyperV_Topology` (PowerShell, on the Hyper-V host) draws what vSphere does not [script]:
host → VM (`Compute`), VM → virtual hard disk (`Storage`), **host → vSwitch → VM (`NETWORK`)**,
the vSwitch keyed by its own ID and the VM by `<id>/<name>`.

## 4. ERT vocabulary

[External Resource Types](https://www.logicmonitor.com/support/external-resource-types) [doc]:
Machines `HyperVisor`, `PhysicalServer`, `VirtualMachine`, `VirtualMachineResource`,
`HyperVisorCluster`; Storage `StorageNode`, `PhysicalDisk`, `VirtualDisk`, `VMDatastore`,
`StorageVolume`, `STORAGELUN`, `StorageAggregate`, `STORAGEINITIATOR`. The ERT sets the icon.

## 5. The Proxmox side

What the API offers for each layer, all readable by `PVEAuditor` (it holds `Sys.Audit`,
`Datastore.Audit`, `SDN.Audit`) [api]:

| Layer | Endpoint | Gives |
|---|---|---|
| Storage definitions | `/storage` (cluster) | type, path, pool / VG / thin pool, server and export, `shared`, `nodes` |
| Storage per node | `/cluster/resources?type=storage` (already read) | node, size, `shared`, plugin type |
| Guest volumes | guest `config` (already read hourly) | `rootfs`, `mpN`, `scsiN` / `virtioN` / `sataN` / `ideN`, `efidisk0`, `tpmstate0`: `<storage>:<volume>` or a host path |
| ZFS pools | `/nodes/{node}/disks/zfs` | name, size, health |
| LVM / thin pools | `/nodes/{node}/disks/lvm`, `/disks/lvmthin` | VGs and their PVs; thin pools and their VG |
| Physical disks | `/nodes/{node}/disks/list` | device, serial, WWN, `used` (LVM, ZFS, ext4, Ceph OSD), OSD id |
| Ceph pools | `/nodes/{node}/ceph/pool` | pool name, size, usage |
| Node networking | `/nodes/{node}/network` | interfaces by name and type (bridge, bond, VLAN, eth, OVS), `bridge_ports`, `slaves`, `vlan-id`, `active` -- **no MAC address** |
| SDN | `/cluster/sdn/zones`, `/cluster/sdn/vnets` | zones (type, bridge, nodes), VNets (zone, tag) |
| Guest NICs | guest `config` | `netN`: bridge, VLAN tag, MAC |

Consequences [inferred]:

- **The vSphere uplink edge cannot be copied.** Proxmox gives no interface MAC and no CDP/LLDP
  neighbour. A node's physical uplinks still reach the map from the other side: a monitored
  switch's L2 topology, joined to the MACs `addERI_Linux` puts on an SNMP-monitored node.
- **The Hyper-V vSwitch pattern can be copied:** node → bridge → guest from `netN` and
  `/nodes/{node}/network`, and SDN VNets for cluster-wide virtual networks.
- **Storage can go deeper than vSphere:** storage → pool (ZFS pool, LVM VG / thin pool, Ceph pool)
  → physical disk, which vSphere's datastore layer stops short of.
