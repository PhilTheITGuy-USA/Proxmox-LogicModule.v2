# LM Exchange listing -- Proxmox VE

Text for submitting the suite to LogicMonitor Exchange (Exchange readiness review gap 2,
2026-10-27). Exchange shows each module's own name, description, technical notes and keywords,
which the modules already carry; this file holds the suite-level text, for wherever the
submission asks for a description of the whole package, and the checks to make before
submitting.

## Short description

Monitors Proxmox VE clusters and standalone hosts through the Proxmox VE REST API: cluster
health and quorum, nodes, every VM and container, storage, Ceph, backups, snapshots,
replication, certificates, subscriptions and topology, with active/standby failover for the
cluster modules.

## Overview

The Proxmox VE package monitors Proxmox VE clusters and standalone hosts through the Proxmox VE
REST API, with a read-only API token (the `PVEAuditor` role). It has 23 LogicModules: 19
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

**Topology.** Modelled on LogicMonitor's vSphere suite: cluster to node to guest, with guests
matched to their own resources by MAC address, so a node's alerts explain its guests'; storage
from each guest and node to its stores, labelled local, shared or network, and on to the ZFS,
LVM and Ceph pools and the disks beneath them; and networking from each node through its
bridges and bonds, or SDN VNets, to its guests.

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
cluster, including failover and hand-back of the cluster role. Ceph, replication and a cluster
address are designed for but not yet seen on a live cluster.

## Modules

| Module | Type | Interval |
|---|---|---|
| `Proxmox_VE_Cluster_Status` | DataSource | 5 min |
| `Proxmox_VE_Cluster_Nodes` | DataSource | 5 min |
| `Proxmox_VE_Cluster_GuestStatus` | DataSource | 3 min |
| `Proxmox_VE_Cluster_GuestPerformance` | DataSource | 5 min |
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
| `Proxmox_VE_Info` | PropertySource | -- |
| `addERI_Proxmox_VE_Node` | PropertySource (ERI) | 30 min |
| `addERI_Proxmox_VE_GuestResource` | PropertySource (ERI) | 30 min |

Keywords: proxmox, pve, proxmox ve, hypervisor, virtualization, cluster, node, qemu, lxc, vm,
container, ceph, storage, backup, snapshot, replication, topology, quorum, ha.

## Before submitting

- [ ] The repository is **public**, and the product page's *(not yet public)* marker beside its
      link is removed. The listing and the product page both send readers there.
- [ ] `python ../lab-scrub/check.py` is clean, and the repo's history holds no lab details
      (rewritten 2026-10-27).
- [ ] Every module is imported from the current build and collecting; the portal's copies are
      the ones published.
- [ ] Exchange has no other module of these names (checked 2026-10-27: none).
- [ ] Publish the PropertySources before the TopologySource, the order the product page gives
      for import.
