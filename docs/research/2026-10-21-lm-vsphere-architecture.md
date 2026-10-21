# How LogicMonitor monitors vSphere, and what it means for Proxmox VE

Research for the Proxmox VE suite v2, 2026-10-21. Sources: LogicMonitor's
[VMware vSphere Monitoring](https://www.logicmonitor.com/support/vmware-vsphere-monitoring) page,
its [Nutanix Prism Central Monitoring](https://www.logicmonitor.com/support/nutanix-prism-central-monitoring)
page, and the vSphere Enhanced Script NetScan the user supplied (LogicMonitor's code, © LogicMonitor;
summarised here, not copied). Each claim is tagged:

- **[doc]** stated on LogicMonitor's page;
- **[script]** read in the NetScan code;
- **[inferred]** my reading, not stated anywhere, with the evidence given.

## 1. Three module sets, by what is monitored

LogicMonitor splits the vSphere package into three sets, "organized into three distinct categories
based on the components that are being monitored" [doc]:

| Set | Applies to | Purpose | Examples |
|---|---|---|---|
| **vSphere** | the vCenter resource [doc] | "availability and performance monitoring for the vSphere environment": clusters, datastores, HA, resource pools, host status, **and VMs** | `VMware_vSphere_Clusters`, `_HighAvailability`, `_DatastoreUsage`, `_HostStatus`, `_VirtualMachinePerformance`, `_VirtualMachineStatus`, `_VirtualMachineSnapshots` |
| **ESXi** | each ESXi host resource [doc] | "availability, performance, and hardware sensor monitoring for VMware ESXi hosts" | `VMware_ESXi_CPU`, `_Memory`, `_Disks`, `_HardwareHealthSensor`, `_NetworkInterfaces`, `_Power` |
| **vCenter Appliance** | the VCSA resource [doc] | the management appliance's own runtime | `VMware_vCenterAppliance_CPU`, `_Services`, `_Backup` |

Around them:

- **PropertySources:** `addCategory_vCenter`, `VMware_vSphere_Info`, `addERI_vCenter` (type
  `PhysicalServer`) and `addERI_ESXi` (type `HyperVisor`) [doc].
- **TopologySources:** `VMware_vCenter_Cluster_Topology`, `_Datastore_Topology`,
  `VMware_vSphere_Network_Topology`, `VMware_vSphere_VirtualMachine_Topology` [doc].
- **LogSources:** ESXi events and vCenter alarms [doc].
- **A troubleshooter:** `VMware_LM_Troubleshooter` "checks various settings to make sure that
  ESXi/vCenter collection will work properly" and "will direct the user on how to fix any discovered
  issues" [doc].

**Standalone ESXi is a separate mode.** It has its own `VMware_ESXi_VirtualMachine*` modules,
"Virtual Machine monitoring for Standalone ESXi hosts not managed by vCenter", gated by the
resource property `standaloneEsxi=true` [doc]. In that mode a host carries its own VMs as
instances, because there is no vCenter to carry them.

The previous generation had VM modules named `VMware_vCenter_VMStatus` / `_VMPerformance`
(VMs as instances under the vCenter resource) and `VMware_ESXi_Topology`. They are listed as
replaced [doc]. Migrating "will not convert instances into Resources" (search summary of the same
page), and running old and new sets in parallel doubles the load on vCenter [doc].

## 2. Resources are created by an Enhanced Script NetScan

Adding resources has two methods: "(Recommended) Advanced NetScan" and "Manually" [doc]. What the
NetScan does [script]:

- **It connects once to vCenter** with `vcenter.user` / `vcenter.pass` / `vcenter.hostname`, using
  LogicMonitor's Collector snippets: `lm.emit`, `lm.debug`, `proto.http`, **`lm.cache`**,
  `vmware.vsphere.api`, `vmware.vsphere.sdk`, and `lm.api`.
- **It creates a resource per VM** with `system.categories=VMwareVM`, `vcenter.vm.name`,
  `vcenter.serial` (`<vCenter UUID>::<VM UUID>`) and `netscan.powerstate`. The VM's hostname is
  its guest IP, or the reverse DNS name of that IP, falling back to its display name. Templates
  are skipped.
- **It creates a resource per ESXi host** with `system.categories=VMware_ESXi`, `esx.user`,
  `esx.pass`, `esx.addr`, and `vcenter.serial` (the service tag, else the hardware UUID). It first
  logs in to each host to check the credentials. A host it cannot log in to is still added, but
  without the category, and with `esx.netscan.status` explaining why. The number of failed logins
  is capped (`esx.maxloginfailures`, default 10).
- **The vCenter appliance VM** gets both `VMware_vCenter` and `VMwareVM`, plus the vCenter
  credentials.
- **Group tree:** the NetScan mirrors vCenter's own inventory:
  - `<rootFolder>/Datacenter - X/Cluster - Y/Resource Pool - Z/...` for the compute view;
  - the VMs and Templates folder view;
  - `.../ESXi hosts` for hosts;
  - `Standalone VMs` and `Standalone ESXi hosts`.

  **VMs are grouped by cluster, resource pool and folder, never by ESXi host**, because a host
  changes on every vMotion.
- **Lineage as properties:** `vcenter.datacenter`, `vcenter.cluster`, `vcenter.resourcepool` and
  `vcenter.folder` on every resource, plus vCenter tags as `vmware.vcenter.tag.<category>`. These
  drive NetScan filters and dynamic groups.
- **Deduplication against the portal:** with LogicMonitor API credentials (`lmaccess.id` /
  `lmaccess.key`), the NetScan looks up existing devices by display name and hostname before
  adding one. A collision is reported and skipped, unless `hostname.source` is `lm` (adopt the
  existing device's name) or `netscan` (rename the new one). `skip.device.dedupe` turns the check
  off. For large portals it queries one device at a time instead of listing them all, within a
  time limit set by `lmapi.timelimit.sec`.
- **Filters** on `vcenter.datacenter`, `.cluster`, `.resourcepool`, `.folder`, `.virtualmachine`,
  `netscan.powerstate` and tags. "If you do not configure the LogicMonitor recommended filters" it
  adds every host and VM. The docs advise onboarding one datacenter or cluster at a time, with an
  auto-balanced Collector group [doc].
- **Lifecycle:** "Subsequent NetScan runs will add or move resources or resource groups", but "the
  NetScan does not have the ability to delete resources" [doc]. It can run as often as hourly.
- **Requires Collector 32.400 or later** [script].

## 3. Where VM metrics are collected [inferred, to verify]

The page does not say whether `VMware_vSphere_VirtualMachinePerformance` applies to the vCenter
resource, with VMs as instances, or to each `VMwareVM` resource. The evidence points to the
vCenter resource:

1. The page files the VM modules under the vSphere set, "for the vSphere environment", which
   applies to vCenter.
2. **The NetScan puts no `vcenter.hostname` and no credentials on an ordinary VM resource** — only
   on ESXi hosts and the vCenter VM [script]. A DataSource on the VM resource would have no way to
   reach vCenter.
3. Standalone ESXi needs its own VM modules on the host [doc], which only makes sense if the normal
   VM modules sit on vCenter.

If this holds, **VMware's model is a hybrid**:

- hypervisor-side VM metrics are instances on the one API endpoint (vCenter);
- each VM is *also* a resource, for grouping, in-guest monitoring (its hostname is the guest's
  IP), alert routing, maintenance windows and access control;
- each hypervisor host is a resource polled directly for host-level data.

This is to be verified in the portal: the Exchange shows each module's AppliesTo.

## 4. Mapping to Proxmox VE

| vSphere | Proxmox VE | Difference that matters |
|---|---|---|
| vCenter (single management API) | the cluster. **Any node answers the cluster API**; there is no separate manager | Today the suite uses the token-holding node as its "vCenter". If that node is down, all cluster-level monitoring stops. |
| ESXi host resource, polled directly | node resource. `/nodes/{self}/...` answers on the node itself | Today the node-scoped modules fan out from the token holder, one call per node |
| VM resource from the NetScan | guest resource from a NetScan | Proxmox offers a guest IP only through the QEMU guest agent; the MAC is always in the config |
| `standaloneEsxi=true` mode | standalone Proxmox host: cluster and node are the same machine | Same split |
| `vcenter.serial` = `<vCenter UUID>::<VM UUID>` | `<cluster>::<vmid>`, or the QEMU `smbios1` UUID | A guest's identity must survive migration: the vmid does, the node does not |
| Lineage properties and folders | cluster, node, pool, tags (`/cluster/resources` carries `pool`, `tags`) | Proxmox pools and tags play the part of resource pools, folders and vCenter tags |
| vCenter tags as properties | Proxmox guest tags | A direct equivalent |
| `lm.api` deduplication | the same snippet | Answers "the guest is already a resource through SNMP or WMI" |
| `lm.cache` | the same snippet | The candidate for sharing one `/cluster/resources` response across many resources |

## 5. What this answers from the v1 discussion

- **"Guests under the host they run on."** VMware does not do this in the resource tree either:
  VMs sit under cluster and resource pool, because hosts change with vMotion. The host
  relationship is drawn by the TopologySource, and is available as a property for dynamic groups.
  A Proxmox NetScan can set `pve.node` on each guest resource, kept current on every hourly run,
  so a dynamic group per node is one AppliesTo for anyone who wants that view.
- **Duplicate guest resources:** the `lm.api` deduplication in VMware's NetScan is the pattern to
  follow.
- **API cost:** VMware keeps VM metrics on the one API endpoint [inferred, §3], so creating VM
  resources does not by itself multiply API calls.
- **The cluster vertex** can be a real resource in v2, so it is drawn (v1 DESIGN §7, spike 3).
