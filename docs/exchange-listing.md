# LM Exchange listing -- Proxmox VE

Suite-level text for LogicMonitor, should it publish the suite on Exchange (Exchange readiness
review gap 2, 2026-10-27; current to v2.8.0). Exchange shows each module's own name, description,
technical notes and keywords, which the modules already carry; this file holds the text for
wherever a description of the whole package is needed, and the checks to make before publishing.

## Short description

Monitors Proxmox VE clusters and standalone hosts through the Proxmox VE REST API: a cluster
health score, quorum and HA, nodes, every VM and container, storage, Ceph, backups, snapshots,
replication, certificates, subscriptions and topology, with active/standby failover for the
cluster modules.

## Overview

The Proxmox VE package monitors Proxmox VE clusters and standalone hosts through the Proxmox VE
REST API, with a read-only API token (the `PVEAuditor` role). It has 25 LogicModules: 21
DataSources, a TopologySource and three PropertySources.

**How it applies.** An Enhanced Script NetScan, one per cluster, creates a resource for each
node and, where the cluster has an address of its own (a VIP, proxy or DNS name), one for the
cluster; it can also add guests as resources. It groups them and sets the categories
(`ProxmoxVE_Cluster`, `ProxmoxVE_Node`, `ProxmoxVE_Guest`) and the `pve.cluster.role` property
that the modules apply on. The package has **no `addCategory_*` PropertySource**: the modules
apply only by those categories and that property, so no module ever applies itself to a
resource that is not Proxmox. A resource can also be set up by hand, with the properties the
product page lists.

**What Exchange does not carry.** The NetScan script and the two dashboards (Proxmox VE, and
Proxmox VE Infrastructure) come from the suite's repository,
[github.com/PhilTheITGuy-USA/Proxmox-LogicModule.v2](https://github.com/PhilTheITGuy-USA/Proxmox-LogicModule.v2),
together with the product page that covers setup end to end.

**Cluster modules** collect once per cluster, on the active resource; a standby resource takes
over while the active one is down or has lost quorum, and hands back with its alerts cleared,
so a cluster never alerts twice. They fail over across the nodes' addresses. Most make a fixed
number of API calls per poll whatever the cluster's size; the few that read each guest run
hourly and ask each guest's own node.

**Node modules** collect on each node's own resource and ask only that node, so a node's alerts
are raised, routed and silenced on the node.

**Cluster health.** `HealthScore` is the lowest of five areas: quorum, nodes online, HA, storage
and node pressure. Each area but quorum is its own datapoint, so the score also shows where to
look. It is for dashboards and raises no alert, as those causes alert on their own. Storage alerts
scale with a store's size, so a large store is not treated as an emergency at 90% full, while
ZFS and Ceph stores keep their real percentage.

**Topology.** Modelled on LogicMonitor's vSphere suite: cluster to node to guest, with guests
matched to their own resources by MAC address, so a node's alerts explain its guests'; storage
from each guest and node to its stores, labelled local, shared or network, and on to the ZFS,
LVM and Ceph pools and the disks beneath them; and networking: each node's bridges, the VLANs
guests use on them and, where SDN is set up, its zones and VNets, down to the guests on them.

**Requirements.** A Collector at version 32.400 or later (the Enhanced Script NetScan),
HTTPS to port 8006 and ping to every node, a Proxmox VE API token with `PVEAuditor` on `/`, and a
LogicMonitor API token with View on Resources for the NetScan's duplicate checking (required
only for guest resources).

**Cost.** Every resource is one host unit; instances are free. Guest resources are therefore
off by default: every guest's hypervisor metrics are collected on the cluster resource either
way.

**Units.** Each datapoint's name ends in its unit; MB and GB are decimal. Status datapoints name
their values, so alerts read "Offline (0)" rather than "0".

**Verified.** Imported into a LogicMonitor portal and run against a two-node Proxmox VE 9.2.3
cluster and a standalone host, including failover and hand-back of the cluster role. The alerts
were driven on the lab: a stopped HA manager, a wrong node clock, a paused VM, guest contention
and the health score's areas. The troubleshooter was seen to find a wrong node name, raise its
alert, clear it once fixed and drop the finding. Ceph, ZFS, replication, a cluster address,
Proxmox VE 8 and true scale are designed for but not yet seen on a live system. The architecture
spec's §5 records what each test showed.

## Modules

| Module | Type | Interval |
|---|---|---|
| `Proxmox_VE_Cluster_Status` | DataSource | 5 min |
| `Proxmox_VE_Cluster_Nodes` | DataSource | 5 min |
| `Proxmox_VE_Cluster_GuestStatus` | DataSource | 3 min |
| `Proxmox_VE_Cluster_GuestPerformance` | DataSource | 5 min |
| `Proxmox_VE_Cluster_VMHealth` | DataSource | 5 min |
| `Proxmox_VE_Cluster_StorageCapacity` | DataSource | 10 min |
| `Proxmox_VE_Cluster_Ceph` | DataSource | 5 min |
| `Proxmox_VE_Cluster_CephOSD` | DataSource | 5 min |
| `Proxmox_VE_Cluster_CephPools` | DataSource | 5 min |
| `Proxmox_VE_Cluster_SDN` | DataSource | 5 min |
| `Proxmox_VE_Cluster_Backups` | DataSource | 60 min |
| `Proxmox_VE_Cluster_Snapshots` | DataSource | 60 min |
| `Proxmox_VE_Cluster_Topology` | TopologySource | 60 min |
| `Proxmox_VE_Node_Detail` | DataSource | 5 min |
| `Proxmox_VE_Node_Services` | DataSource | 5 min |
| `Proxmox_VE_Node_Replication` | DataSource | 10 min |
| `Proxmox_VE_Node_StoragePools` | DataSource | 10 min |
| `Proxmox_VE_Node_Network` | DataSource | 5 min |
| `Proxmox_VE_Node_Disks` | DataSource | 240 min |
| `Proxmox_VE_Node_Certificates` | DataSource | 240 min |
| `Proxmox_VE_Node_Subscription` | DataSource | 720 min |
| `Proxmox_VE_Troubleshooter` | DataSource | 5 min (checks every 15 min) |
| `Proxmox_VE_Info` | PropertySource | -- |
| `addERI_Proxmox_VE_Node` | PropertySource (ERI) | 30 min |
| `addERI_Proxmox_VE_GuestResource` | PropertySource (ERI) | 30 min |

Keywords: proxmox, pve, proxmox ve, hypervisor, virtualization, cluster, node, qemu, lxc, vm,
container, ceph, storage, backup, snapshot, replication, topology, quorum, ha, health score, guest agent,
troubleshooter.

## Before publishing

- [x] The repository is **public** (confirmed 2026-11-05). The listing and the product page both
      send readers there.
- [ ] `python ../lab-scrub/check.py` is clean, and the repo's history holds no lab details
      (rewritten 2026-10-27).
- [ ] Every module is imported from the current build and collecting; the portal's copies are
      the ones published.
- [ ] Exchange has no other module of these names (checked 2026-10-27: none).
- [ ] Publish the PropertySources before the TopologySource, the order the product page gives
      for import.
