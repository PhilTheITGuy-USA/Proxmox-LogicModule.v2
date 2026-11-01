# Where LogicMonitor's hypervisor suites put a VM's hypervisor data

Date: 2026-10-31. Read from LogicMonitor's support pages and one community post, plus the vSphere
exports read on 2026-10-25 (`2026-10-21-lm-vsphere-architecture.md` §3). Claims are tagged
**[doc]** (LogicMonitor documentation or LogicMonitor's community site, linked), **[script]**
(read in a module export or NetScan script) or **[inferred]**.

Purpose: the architecture spec §6 keeps "per-guest Proxmox API modules on guest resources" out
of scope. The user asked (2026-10-31) how LogicMonitor's other hypervisor suites handle the same
case, the hypervisor's view of one VM shown on that VM's own resource.

## 1. Summary

No LogicMonitor suite whose module placement is published puts hypervisor API modules on VM
resources. Each collects VM status and performance as instances on its management endpoint
(vCenter, Prism Central, the Hyper-V host, the XenServer pool master). Where VMs are also
resources, they are there for grouping, in-guest monitoring and alert dependencies. The one
example of hypervisor data on a VM resource is a community post its author calls unsupported
(§3). That is the model the v2 suite already follows (spec §3.2, §3.6).

## 2. Suite by suite

| Suite | VM hypervisor data | VMs as resources | Evidence |
|---|---|---|---|
| VMware vSphere | Instances on the vCenter resource: `VMware_vSphere_VirtualMachineStatus` applies on `system.virtualization =~ "VMware ESX vCenter"` [script]. The `VMware_ESXi_VirtualMachine*` copies apply to a host only with `standaloneEsxi` true [script] [doc] | Yes, `VMwareVM`, created by the NetScan "to match your vCenter organizational structure" [doc]; it gives an ordinary VM resource no vCenter hostname or credentials [script], so no API module could run there | Exports read 2026-10-25. The support page describes the VM modules as monitoring "individual VMs" [doc], which is loose wording, not a different placement |
| Nutanix Prism Central | `Nutanix_PrismCentral_VMs`, "Prism Central Virtual Machine Performance and Utilization metrics" [doc]; instances on Prism Central [inferred] | The page warns that an unfiltered NetScan "attempts to add all discovered Nutanix hosts and VMs as individual LogicMonitor resources" [doc], but the NetScan script it shows creates only hosts and the Prism Central resource [script] | No AppliesTo published; the page contradicts itself |
| Microsoft Hyper-V | `Win_HyperV_VirtualMachines`, VMs on Windows Server 2008 and later, "summary statistics" [doc]; read through WMI on the Hyper-V host, VMs as instances [inferred] | No NetScan; a VM is a resource only if added for in-guest monitoring [inferred] | Integration page and module list only |
| Citrix XenServer | "All Xen specific monitoring data for the entire pool will be collected from the pool master"; pool members are added "for collection of device specific performance data and alerts" [doc] | Not mentioned | The page does not name its VM modules |
| Cisco HyperFlex | `Cisco_HyperFlex_HypervisorVMS`: VM and guest OS state, storage, template origin [doc] | Not stated | Module list only |

## 3. What LogicMonitor does for VM resources

**Dependent Alert Mapping for virtual machines** [doc]. VMs "added as resources" are given the
category `VirtualMachineResource` by a PropertySource, which links each to its host; when the
host goes down, "the alert will be sent for the parent resource" and the VMs' alerts are
suppressed. VM instances are excluded. The package's modules are the instance modules of §2
(`VMware_vSphere_VMstatus`, `VMware_vCenter_VMStatus`, `Win_HyperV_VirtualMachines`, the
HyperFlex VM modules and others). So LogicMonitor's work on VM resources quiets them under a
failed host; it does not move hypervisor data onto them. It is the parent-only alerting the v2
suite applies to VLANs and VNets (spec §3.4).

**The `*_InGuest` DataSources** (community post, not LogicMonitor documentation) [doc]. Copies
of `VMware_vSphere_VMperformance`, `_VMsnapshots` and `_VMstatus` that apply to a Windows VM
resource carrying `esx.url`, `esx.user` and `esx.pass`, each discovering only its own VM. This
is exactly the case §6 excludes. The author states they "are custom development and officially
unsupported by LogicMonitor", and lists the costs:

- credentials on each VM resource (set on a dynamic group of VMs);
- extra vCenter load, so roll out in stages;
- Windows VMs only (`system.model` matched); Linux VMs are not detected;
- the VM-to-vCenter link has to be set by hand, and pointing `esx.url` at a host "breaks when
  the VM moves".

## 4. What this means for the Proxmox suite

- §6's first line matches what LogicMonitor ships: guests' hypervisor data stays as instances on
  the cluster role.
- The gap the spec found in §3.6 -- role permissions are set on resource groups only, so a
  tenant given its guests' group cannot see their hypervisor data -- is open in LogicMonitor's
  own suites too [inferred]. Dependent Alert Mapping does not close it.
- The community workaround's costs are the arguments against per-guest modules in the Proxmox
  case: the token on every guest resource, O(selected guests) API calls where the cluster role
  makes one, and a guest that migrates. Proxmox adds one the post does not meet: a guest
  resource that stops answering ping is dead and suppresses its own alerts (spec §3.1, S2), so
  guest status could not move there.
- If the tenant gap is closed later, a read-only PropertySource on the guest resource (node,
  VMID, state, agent status as properties; no token on the guest) avoids those costs; it has
  no LogicMonitor precedent [inferred].

## Sources

- [VMware vSphere Monitoring](https://www.logicmonitor.com/support/vmware-vsphere-monitoring)
- [Nutanix Prism Central Monitoring](https://www.logicmonitor.com/support/nutanix-prism-central-monitoring)
- [Enabling Dependent Alert Mapping for Virtual Machine](https://www.logicmonitor.com/support/enabling-dependent-alert-mapping-for-virtualmachine)
- [Citrix XenServer Monitoring](https://www.logicmonitor.com/support/monitoring/os-virtualization/citrix-xenserver-monitoring)
- [Microsoft Hyper-V integration](https://www.logicmonitor.com/integrations/microsoft-hyper-v)
- [VMware vSphere In-Guest Monitoring](https://community.logicmonitor.com/discussions/archive/vmware-vsphere-in-guest-monitoring/4355) (community)
