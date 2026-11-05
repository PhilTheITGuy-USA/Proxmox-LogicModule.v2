# Proxmox VE Monitoring

> **Status, October 2026.** The suite is imported into a LogicMonitor portal and runs against a
> two-node Proxmox VE 9.2.3 cluster. Anything marked *(not yet verified)* is how the suite is
> designed to behave in a setup that cluster does not have, such as Ceph, replication or a
> cluster address.

LogicMonitor monitors Proxmox VE clusters and standalone hosts out of the box through the Proxmox
VE REST API. One NetScan per cluster creates a resource for the cluster, one for each node and,
if you choose, one for each guest, groups them, and gives them the properties the LogicModules
apply on. Cluster-wide health, storage, Ceph, backups and every guest's hypervisor metrics are
collected by the cluster modules: on a cluster resource when the cluster has an address of its
own, otherwise on one node, with a second node standing by to take over. Each node's own health
is collected on that node.

For more information about the API, see:

- [Proxmox VE API viewer](https://pve.proxmox.com/pve-docs/api-viewer/)
- [Proxmox VE API documentation](https://pve.proxmox.com/wiki/Proxmox_VE_API)

## Technology Type

Server & Operations Hardware

## Compatibility

As of October 2026, the Proxmox VE package is known to be compatible with:

- Proxmox VE 9.2.3, two-node cluster (lab-verified)
- Proxmox VE 9.2.3, standalone host (lab-verified)
- Proxmox VE 8.x *(not yet verified; see Setup Requirements for the guest-agent privilege)*

Designed for up to 10 clusters of 16 nodes and 1,000 guests each. *(not yet verified at true scale)*

## Setup Requirements

- The Proxmox VE LogicModules (see [LogicModules in Package](#proxmox-ve-logicmodules-in-package))
- The NetScan script and the dashboards, which LM Exchange does not carry (see
  [Getting the NetScan Script and Dashboards](#getting-the-netscan-script-and-dashboards))
- A Collector at version 32.400 or later, which the Enhanced Script NetScan requires
- HTTPS access from that Collector to port 8006 on **every** node, not only the API address:
  each node's modules ask that node directly
- **Ping (ICMP) from that Collector to every node**, and to the cluster address if you use one.
  The modules collect through the API, but LogicMonitor decides whether a resource is up from
  ping: a node it cannot ping shows as down, and its alerts are held back
- One Proxmox VE API token per cluster, with the permissions below
- A LogicMonitor API token for the NetScan, required for guest resources and recommended otherwise
  (see [Creating a LogicMonitor API Token for the NetScan](#creating-a-logicmonitor-api-token-for-the-netscan))
- Optionally, an address for the cluster as a whole (see
  [Giving the Cluster One Address](#giving-the-cluster-one-address-optional))

| Category | User Permissions Required |
|---|---|
| Cluster, node, storage, Ceph, backup and guest metrics | `PVEAuditor` role on `/`, propagated |
| A guest's live address (NetScan, Proxmox VE 9) | `VM.GuestAgent.Audit`, which `PVEAuditor` includes |
| A guest's live address (NetScan, Proxmox VE 8) | `VM.Monitor`, which `PVEAuditor` does not include *(not yet verified)* |
| Whether a VM's guest agent answers (VM Health, Proxmox VE 9) | `VM.GuestAgent.Audit`, which `PVEAuditor` includes |
| Whether a VM's guest agent answers (VM Health, Proxmox VE 8) | `VM.Monitor`, which `PVEAuditor` does not include *(not yet verified)* |

**Note:** Without the guest-agent privilege the NetScan still runs: it uses the address in the
guest's configuration, or the guest's name, as the hostname, and reports that it did. VM Health
leaves `AgentResponding` empty (No Data) rather than reading 0, and `Paused` still reports.

### Getting the NetScan Script and Dashboards

LM Exchange carries LogicModules only. The NetScan script and the two dashboards come from the
suite's repository, [github.com/PhilTheITGuy-USA/Proxmox-LogicModule.v2](https://github.com/PhilTheITGuy-USA/Proxmox-LogicModule.v2) *(not yet public)*:

| File | Where in the repository |
|---|---|
| `Proxmox_VE_NetScan.groovy` | `dist/netscan/`, after `python build/build.py` |
| `Proxmox_VE.json`, `Proxmox_VE_Infrastructure.json` (dashboards) | `dashboards/`, or `dist/dashboards/` after the build |

The build also writes every module's JSON to `dist/`, for a portal without LM Exchange access.
Without the NetScan, the modules apply only to resources you categorise by hand (see
[Manually Assigning Properties to Resources](#manually-assigning-properties-to-resources)).

### Creating an API Token for Proxmox VE

On any node of the cluster, as root:

```sh
pveum user add monitor@pve --comment 'LogicMonitor'
pveum acl modify / --users monitor@pve --roles PVEAuditor
pveum user token add monitor@pve logicmonitor --privsep 1
pveum acl modify / --tokens 'monitor@pve!logicmonitor' --roles PVEAuditor
```

Proxmox shows the token's secret **once**, when it is created. Copy it then; a lost secret means
a new token.

**Note:** With privilege separation on (`--privsep 1`, recommended), the token has only the
rights of its own ACL, so the last command is required. A token created with `--privsep 0` has
exactly its user's rights instead.

The value LogicMonitor needs is the whole token string, `user@realm!tokenid=secret`, for example
`monitor@pve!logicmonitor=<secret>`.

### Creating a LogicMonitor API Token for the NetScan

The NetScan reads your portal's existing resources through the LogicMonitor API. It uses that
read to:

- check whether the portal already monitors a node or guest before adding it, by display name,
  hostname or address, so it never adds a duplicate (each duplicate costs a host unit);
- recognise a guest it added before by its cluster and VMID (`pve.serial`), so a guest whose IP
  address changes stays one resource instead of becoming two;
- report resources whose node or guest no longer exists in Proxmox.

**Guest resources are created only with this token.** Without it the NetScan cannot tell a new
guest from one already monitored, so it adds nodes and the cluster role but no guests, and its
report says why. The token only reads: the NetScan never changes a resource through it.

| Permission | Access | Why |
|---|---|---|
| Resources | **View**, on all resource groups | A duplicate can be in any group, for example a guest someone added over SNMP elsewhere. A role that sees only some groups misses those and lets the NetScan add a second resource |
| Everything else | None | Not used |

1. In LogicMonitor, navigate to **Settings > Users and Roles > Roles**, select **Add**, name the
   role (for example `NetScan Read`), and give it **View** on **Resources** for all resource
   groups. Nothing else.
2. Under **Users**, add an API-only user (for example `netscan-proxmox`) with that role. The
   built-in **readonly** role is known to work; a role with only View on Resources should, but is
   *(not yet verified)*.
3. Open the user, select **API Tokens > Add**, and choose an **LMv1** token.
4. Copy the **Access ID** and the **Access Key**. The key is shown only once.
5. Enter them in the NetScan's custom credentials (step 7 below) as `lmaccess.id` and
   `lmaccess.key`. The NetScan screen masks the key.

**Note:** One token can serve every Proxmox NetScan in the portal.

### Giving the Cluster One Address (Optional)

Proxmox VE has no cluster address of its own: every node manages the whole cluster. The suite
works without one. Where you already have, or want, a single address for the cluster, the
NetScan uses it for a separate cluster resource:

| Method | What it is | Notes |
|---|---|---|
| Virtual IP | keepalived (VRRP) on the nodes moves one IP between them; it must answer ping and port 8006 | Not supported by the Proxmox team. Use a free static IP; community reports advise one outside the nodes' subnet |
| Reverse proxy or load balancer | HAProxy, nginx or a commercial balancer in front of every node's port 8006 | Keeps extra software off the hypervisors; give it a certificate that names the proxy |
| DNS name | a name pointing at a VIP or proxy | As for the address it points to |
| Round-robin DNS | one name, one record per node | Works, but not recommended: LogicMonitor's ping can land on a node that is down, which flaps the cluster resource |

**Note:** Each node's own certificate does not name the shared address. Install one that does,
or set `pve.api.insecure=true` where the network is trusted.

How the cluster modules are placed:

| The cluster has | Collects normally | Stands by, and collects only when the first is down |
|---|---|---|
| an address from the table above | a cluster resource with that address as its hostname | the node with the lowest node ID |
| no address | the node with the lowest node ID | the node with the next node ID |

A resource needs an address that answers, or LogicMonitor shows it as down and holds back its
other alerts. That is why, without a cluster address, the cluster modules live on nodes.

## Add Resources into Monitoring

You can add Proxmox VE resources using the following methods:

- (Recommended) Advanced NetScan
- Manually

### Using Advanced NetScan to Add Proxmox VE Resources (Recommended)

For a given Proxmox VE cluster, the Enhanced Script NetScan creates this group structure and
the resources in it:

```
Proxmox VE/                     the root group (pve.netscan.rootFolder)
  Cluster - <cluster>/          the cluster resource
    Nodes/                      one resource per node
    Guests/                     only with pve.netscan.guests=true
  Host - <node>/                a standalone host instead of Cluster - …
```

The NetScan creates only these groups, whose membership never changes: it adds a resource to a
group but never takes it out of one, so a group by pool would keep every guest that ever passed
through it. Group guests by pool, tag or node with **dynamic groups** instead, on `pve.pool`,
`pve.tags` or `pve.node` (for example, Applies To `pve.pool == "web"`); they follow the guest's
current values. Run one NetScan per cluster.

**Warning:** Each resource uses one host unit of your licence; instances do not. A cluster of
16 nodes is 16 host units, or 17 with a cluster address; each guest you add as a resource is one
more. Guest resources are
therefore off by default, and every guest's hypervisor metrics are collected on the cluster
resource either way.

1. In LogicMonitor, navigate to **Settings > NetScans**, select the add icon, then
   **Add Advanced NetScan**.
2. Enter a **Name**, for example `Proxmox VE - <cluster>`, and optionally a **Description**.
3. Select the **NetScan Group**.
4. Select the **Collector Group** and **Preferred Collector**. It must reach port 8006 on every
   node.
5. From **Discovery Method**, select **Enhanced Script NetScan**.
6. Optionally, turn on the email notification when the scan finishes.
7. Under **Resource Credentials Options**, select **Use custom credentials for this scan**, then
   **Add** each property below. A value whose name ends in `.credential` is masked.

   | Property | Value | Example | Required |
   |---|---|---|---|
   | `pve.api.url` | The API address of any node, a VIP or a DNS name for the cluster | `https://pve1.example.com:8006` | Yes |
   | `pve.token.credential` | The whole token string | `monitor@pve!logicmonitor=<secret>` | Yes |
   | `lmaccess.id` | The LogicMonitor API token's Access ID (see [Creating a LogicMonitor API Token for the NetScan](#creating-a-logicmonitor-api-token-for-the-netscan)) | `a1b2c3d4e5f6` | Required with `pve.netscan.guests=true`; recommended otherwise |
   | `lmaccess.key` | The token's Access Key | `<key>` | With `lmaccess.id` |
   | `pve.netscan.cluster.address` | The cluster's own address, if it is not the address in `pve.api.url` | `pve-vip.example.com` | No |

   Optional settings:

   | Property | Value | Examples | Default Value |
   |---|---|---|---|
   | `pve.api.insecure` | `true` accepts Proxmox's self-signed certificate | `true` | `false` |
   | `pve.api.timeout` | Connect and read timeout, ms | `20000` | `10000` |
   | `pve.api.concurrency` | Requests in flight per node in per-guest loops | `4` | `2` |
   | `pve.netscan.rootFolder` | Root group name | `Datacenter East/Proxmox` | `Proxmox VE` |
   | `pve.netscan.nodes` | `false` creates only the two resources that carry the cluster modules | `false` | `true` |
   | `pve.netscan.node.hostname` | `ip`: the node's cluster address; `name`: its node name, which must resolve | `name` | `ip` |
   | `pve.netscan.node.maxfailures` | Node checks that may fail in one run before the rest are skipped | `5` | `10` |
   | `pve.netscan.guests` | `true` adds guests as resources (one host unit each) | `true` | `false` |
   | `pve.netscan.guest.hostname` | `ip`: the guest's address; `name`: its name, where guest names resolve in DNS | `name` | `ip` |
   | `pve.netscan.guests.unaddressed` | `true` also adds selected guests with no address the NetScan can read; they show as down | `true` | `false` |
   | `skip.device.dedupe` | `true` turns duplicate checking off | `true` | `false` |
   | `hostname.source` | On a duplicate: unset skips it and reports it; `lm` adopts the existing resource, keeping its hostname and display name on every run (see step 10); `netscan` adds a second one | `lm` | unset |
   | `lmapi.timelimit.sec` | Above this estimate, existing resources are looked up one at a time | `90` | `60` |

8. In **Filters**, choose which guests become resources. A guest must pass every filter that is
   set; templates never become resources.

   | Property | Description | Example |
   |---|---|---|
   | `pve.netscan.guests.filter.pool` | Proxmox pools, comma-separated | `prod,web` |
   | `pve.netscan.guests.filter.tag` | Proxmox tags, comma-separated | `monitor` |
   | `pve.netscan.guests.filter.node` | Node names, comma-separated | `pve1,pve2` |
   | `pve.netscan.guests.filter.vmid` | VMIDs and ranges | `100-199,250` |
   | `pve.netscan.guests.filter.type` | `qemu` or `lxc` | `qemu` |
   | `pve.netscan.guests.filter.status` | `running` or `stopped` | `running` |

   Whether the Filters section's own rows also apply to these is *(not yet verified)*; setting
   them as properties in step 7 always works.

9. Under **Script**, select **Embed a Groovy script** and paste the NetScan script,
   `Proxmox_VE_NetScan.groovy` (see
   [Getting the NetScan Script and Dashboards](#getting-the-netscan-script-and-dashboards)).

   **Warning:** Do not edit the script. Change its behaviour only through the properties above.

10. Leave **Ignore system.ips when checking existing Resources for duplicates** off, and
    **Exclude duplicate IP addresses** at **Matching any Monitored Resources**. Set **Criteria for
    updating Existing Resources** to **Resources discovered by this NetScan** (the default). Never
    **Do not update any existing resources**: the suite depends on each run updating its
    resources.

    To have the NetScan **adopt** nodes or guests you already monitor (over SNMP or WMI, for
    example), so they gain the Proxmox properties and groups instead of being skipped, also:
    - add the property `hostname.source` = `lm`;
    - set **Criteria for updating Existing Resources** to **All matching resources**;
    - set **Exclude duplicate IP addresses** to **Matching resources in these resource groups**
      and select no group, or an empty one. Adoption under the default exclusion is *(not yet
      verified)*.

    An adopted resource keeps its hostname, display name, categories and history, and stays in
    its own groups as well; the run's report lists it as `adopted:`.
11. In **Schedule**, select **Run this NetScan on a schedule**. Hourly is recommended; a run with
    guests off costs two API calls plus one per node.
12. Select **Save** or **Save and Run**.

**Recommendation:** If the cluster has an address of its own (see
[Giving the Cluster One Address](#giving-the-cluster-one-address-optional)), put it in
`pve.api.url`. The NetScan's output says which arrangement it chose, for example "no cluster
address: active pve1, standby pve2".

A guest resource's hostname is the guest's live IP address, read from the container's interfaces
or the VM's guest agent on the interface that carries the guest's first network card's MAC
address (normally `net0`); else the static
address in its configuration. A guest with neither is not added, and the run reports it: its name
would not answer ping, so it would show as down. It is never replaced by a DNS name, so that
LogicMonitor's duplicate-IP check recognises a guest you already monitor. Where guest names
resolve (dynamic DNS), `pve.netscan.guest.hostname=name` uses the name instead.

**Warning:** Guest resources need the LogicMonitor API credentials. The NetScan recognises a guest
it added before by its cluster and VMID, so a guest whose address changes stays one resource;
without the credentials it cannot look, and it adds no guests rather than risk duplicates.

## Manually Assigning Properties to Resources

If you add resources through the Advanced NetScan, it sets every property below and keeps it
current. Add resources manually only where a NetScan cannot run, and give each the category of
its role in `system.categories`. The suite has no `addCategory_*` PropertySource that would set
them for you: the modules apply only by these categories and `pve.cluster.role`, so that a
module never applies itself to a resource that is not Proxmox.

### Cluster Role

The resources that carry the cluster modules: a cluster resource and a standby node, or two
nodes. A node carrying the role keeps its node properties too.

| Property | Value | Example values | Required |
|---|---|---|---|
| `system.categories` | `ProxmoxVE_Cluster` on a cluster resource; `ProxmoxVE_Node` on a node; both on a standalone host | `ProxmoxVE_Cluster` | Yes |
| `pve.cluster.role` | `active`, `standby` or `none`; the cluster modules apply where it is `active` or `standby` | `standby` | Yes |
| `pve.cluster.active` | On the standby: the active node's name, or `address:<host>` for a cluster resource; `none` elsewhere | `address:pve-vip.example.com` | Yes |
| `pve.token.credential` | The whole token string | `monitor@pve!logicmonitor=<secret>` | Yes |
| `pve.api.url` | API address | `https://pve-vip.example.com:8006` | Yes |
| `pve.api.hosts` | Every node's address, for failover | `10.0.0.11,10.0.0.12` | Recommended |
| `pve.cluster` | The cluster name | `prod-east` | Yes |
| `pve.api.insecure` | Accept self-signed certificates | `true` | No |

### Node Resource

| Property | Value | Example values | Required |
|---|---|---|---|
| `system.categories` | `ProxmoxVE_Node` | `ProxmoxVE_Node` | Yes |
| `pve.token.credential` | The cluster's token string | `monitor@pve!logicmonitor=<secret>` | Yes |
| `pve.node` | The node's Proxmox name, as `pvecm nodes` shows it | `pve1` | Recommended; otherwise the node asks the API which node it is |
| `pve.cluster` | The cluster name | `prod-east` | Recommended |
| `pve.api.insecure` | Accept self-signed certificates | `true` | No |

The node's hostname should be its cluster address, so its topology vertex matches without SNMP.

### Guest Resources Monitored From Inside

A guest that you monitor as its own resource, over SNMP or WMI, is labelled a virtual machine
(`VirtualMachineResource`) on topology maps by `addERI_Proxmox_VE_GuestResource`, which
recognises the guest by its MAC address. A guest that the NetScan created or adopted needs nothing
here: the NetScan sets `pve.guest.mac` from Proxmox, and that is used whatever the prefix.

Without the NetScan, the module recognises a Linux or Windows resource whose
`auto.network.mac_address` starts with `bc:24:11`, Proxmox's default guest prefix. If your
cluster hands out a different prefix (**Datacenter > Options > MAC address prefix**), set it on
the resource group that holds those guests:

| Property | Value | Example values | Required |
|---|---|---|---|
| `pve.guest.mac_prefix` | The MAC prefixes your guests use, comma-separated, in either case | `bc:24:11,02:00:00` | Only if the cluster's prefix is not `bc:24:11` |

The list replaces the default rather than adding to it, so include `bc:24:11` if guests still use
it. A resource whose MAC matches none of the prefixes is left as it is. Not covered: a guest NIC
given a hand-set MAC outside every prefix, a resource whose `auto.network.mac_address` is a
different NIC's MAC, and guests that are neither Linux nor Windows, such as virtual firewalls and
routers, which keep the type their own modules give them.

## Considerations

### Licensing

Every resource is one host unit; instances are free. At 10 clusters of 16 nodes with guests off,
the suite uses 160 host units, or up to 170 if every cluster has an address of its own and so a
cluster resource. Each guest added as a resource adds one.

### Collector Proxies

The modules connect to Proxmox directly and do not use the Collector's proxy settings. The
Collector needs a direct route to port 8006 on every node.

### Modifying the API Port or URL

If the API is behind a VIP, a load balancer or a nonstandard port, set `pve.api.url` to it. The
cluster modules fail over across `pve.api.hosts` when that address does not answer; node modules
always ask their own node.

### Units

Each datapoint's name ends in its unit: `Bytes`, `MB`, `GB`, `Percent`, `Ms`, `Seconds`, `Hours`
or `Days`, or, for a count per second, `IOPS` or `Rate`. MB and GB are decimal (10^6 and 10^9 bytes), as drive and link capacities are
labelled. The units follow the figure's use, so they differ between modules:

| Unit | Modules |
|---|---|
| Bytes, as Proxmox or Ceph reports them | Node Detail (memory, swap, root filesystem, KSM), Cluster Status (memory), Ceph and Ceph OSD (raw use) |
| MB, and MB per second for rates | Guest Performance (memory, balloon, network, disk and guest swap rates) |
| Milliseconds | Guest Performance (disk latency), Ceph OSD (commit and apply latency) |
| GB | Storage Capacity, Node Disks, Backups (latest backup size), Guest Performance (disk) |

LogicMonitor prints a byte count of that size in scientific notation, so the capacities that
dashboard tables show are in GB.

### Instances That Disappear

When Active Discovery stops finding something, what happens depends on whether its going away is
normal or is itself the problem:

| When discovery no longer finds it | Modules | What happens |
|---|---|---|
| Normal: the object was removed | Guest Status, Guest Performance, Snapshots, Backups, Replication, SDN, Node Network, Troubleshooter | The instance leaves the Resources tree; its history is kept 30 days and comes back if the object does |
| The problem: a pool, disk or service went missing | Storage Pools, Storage Capacity, Node Disks, Node Services, Node Certificates, Cluster Nodes, Cluster Status, Ceph OSD, Ceph Pools | The instance stays, with its history, and reads No Data; delete it by hand once the object is gone for good. A disk Proxmox stops listing raises an error instead (Node Disks' `Present`), since a disk that fails off its bus can no longer report SMART |

Proxmox hides from a token what it may not see instead of refusing the request, so to a token
without `VM.Audit` a cluster seems to have no guests, backups or replication jobs. When a module
in the first row finds nothing, it checks the token's own permissions: if the token could not
have seen the objects, discovery fails and names the missing privilege, and the instances stay.
A discovery that fails to read Proxmox fails the same way, rather than retire what it could not
read. *(Seen on the lab for the guest modules, Backups, SDN and Node Network; not yet verified for
Replication.)*

No datapoint alerts on No Data, as in LogicMonitor's own hypervisor modules, so a kept instance
that stops reporting turns grey rather than raising an alert. When collection itself breaks, the
Troubleshooter's instances say why.

### Import LogicModules

Import in this order, so that each module finds what it depends on:

1. The PropertySources, in the portal (**Modules > My Module Toolbox > Add > Import from file**):
   `Proxmox_VE_Info`, `addERI_Proxmox_VE_Node` and `addERI_Proxmox_VE_GuestResource`. The
   ERI PropertySources go before the TopologySource, so the keys the map matches on are on the
   resources when it first runs.
2. The DataSources and the TopologySource, every other module listed under
   [LogicModules in Package](#proxmox-ve-logicmodules-in-package).
3. The dashboards, so every widget finds its module.

The modules apply by category and `pve.cluster.role`, so nothing else needs to be set on the
resources the NetScan creates.

### Upgrading from v2.5.0

**VM Health is a new module.** Proxmox reads a VM as running for as long as its QEMU process
exists, so Guest Status cannot see a VM that has stopped working. `Proxmox_VE_Cluster_VMHealth`
adds an instance for each VM, and containers are left out:

- `Paused` raises an error on the second poll in a row when QEMU has stopped the VM's CPUs,
  usually because its storage filled up or failed. A guest panic, or someone pausing the VM by
  hand, does the same. A backup, migration or snapshot that pauses a VM under a lock does not
  alert.
- `AgentResponding` raises an error on the third poll in a row (10 to 15 minutes) when a VM with
  the QEMU Guest Agent option on stops answering, so its operating system has hung or the agent
  has stopped. A reboot does not page. A VM with the option on and no agent installed reads 0,
  so turn the option off for it.

Guest Status no longer raises `StartAtBootNotRunning` on a paused VM, so a pause gives one alert,
from VM Health. `Status` still reads "Not running" while a VM is paused.

**Node Subscription** gains `RebootRequired`, a warning when a node has a newer kernel installed
than the one it is running, and `UpdatesAvailable`, the number of Proxmox packages with a newer
version in the node's package cache, which ships without a threshold. The module polls every 12
hours, so the warning can take that long to open or clear. A node pinned to an older kernel on
purpose will warn: disable the alert on that node. `UpdatesAvailable` reads No Data on a node
whose package cache knows no repository, until `apt update` has run there.

**Guest Performance** now alerts on contention, on the third poll in a row:

| Datapoint | Warning | Error |
|---|---|---|
| `PressureCPUSomePercent` | 25 | 50 |
| `PressureMemoryFullPercent` | 5 | 15 |
| `GuestSwapInRateMB` | 1 | 10 |

These thresholds are starting points, so tune them on an instance group where a guest runs hot
by design. I/O pressure still ships without a threshold: a camera recorder's normal reads reach
23%.

1. Import `Proxmox_VE_Cluster_VMHealth`, and re-import `Proxmox_VE_Cluster_GuestPerformance`,
   `Proxmox_VE_Cluster_GuestStatus` and `Proxmox_VE_Node_Subscription`. The other modules, the
   NetScan and the dashboards are as in v2.5.0.
2. On Proxmox VE 8, for `AgentResponding`, give the token `VM.Monitor`, which `PVEAuditor` does
   not include *(not yet verified)*. Without it the datapoint reads No Data. Proxmox VE 9 needs
   nothing new.

### Upgrading from v2.4.0

A backup alert now says which guest failed, in which job, and why. The Backups instance's
description, which the alert prints, reads for example "Backed up by Nightly. Last failure in
the run started 2026-11-03 21:00 EST on Node-A: CT is locked (backup)". So the reason is always
there, the failure alerts once discovery, which now runs every 15 minutes, has recorded it:
normally within 15 minutes, and at most an hour after the run ended. A run that fails before it
reaches any guest, for example because its storage is offline, now fails each guest it was
meant to back up, with the run's error as the reason. It used to fail none. A run whose log
and error name neither its guests nor its storage still fails none, rather than guess.

`LastBackupAgeHours` now warns after 8 days without a good backup and raises an error after 14,
which catches a daily or weekly job that has stopped running. For a job that runs less often
than weekly, raise the threshold on its instance group.

Guest Status gains `StartAtBootNotRunning`: a guest set to **Start at boot** in Proxmox that is
not running raises an error once three polls in a row have seen it down: 6 minutes after the
first, up to 9 after it stopped. A restart does not page. A guest
that is locked by a task such as a stop-mode backup, a guest managed by HA, and a guest on a
node that is down do not alert. `Status` still ships without a threshold. To silence a guest
that is meant to stay stopped, turn off its Start at boot. The alert clears at the next
discovery, within the hour.

Discovery now records Start at boot from the guest configurations it already reads, including
when `topo.namespace` or `topo.blacklist` is set; there it still adds no topology keys. Until
that first discovery has run, the new datapoint is empty.

1. Re-import `Proxmox_VE_Cluster_Backups` and `Proxmox_VE_Cluster_GuestStatus`.
   `Proxmox_VE_Cluster_GuestPerformance` and `Proxmox_VE_Cluster_Snapshots` share the discovery
   code Guest Status extends, and their files differ for that alone: they behave as in v2.4.0,
   so re-importing them is optional. The other modules, the NetScan and the dashboards are as
   in v2.4.0.

### Upgrading from v2.3.2

Node Disks gains `Present`: a disk Proxmox no longer lists on its node raises an error alert,
where it used to turn grey. A disk taken out on purpose keeps alerting until its instance is
deleted. Disks are named by device (`sda`, `sdc`), so a reboot that renames them can report a
disk missing under its old name while discovery adds it under the new one.

Collection now reads the instances LogicMonitor hands a script, which it used to skip: each
arrives as a Collector type that is not a Java map. This is what lets `Present` reach a missing
disk. It also gives Node Network's VLAN instances their `Active` value on every poll, where they
read No Data, and lets the troubleshooter clear a problem it knows only from its instance *(not
yet verified)*.

1. Re-import `Proxmox_VE_Node_Disks`, `Proxmox_VE_Node_Network` and `Proxmox_VE_Troubleshooter`.
   The other modules, the NetScan and the dashboards are as in v2.3.2.

### Upgrading from v2.3.1

v2.3.2 is a fix release. v2.3.1 kept guests when the token could not see them; the same now
holds for every module whose instances are deleted after 30 days. Backups, Replication and SDN
discovery fail, rather than report nothing, when the token is not allowed to see what they list
(a token without `VM.Audit` deleted every Backups instance on the lab), and Replication, SDN and
Node Network fail rather than report nothing when Proxmox does not answer. Node Network fails
when it cannot read every guest's network settings, instead of dropping those VLANs. A cluster
that really has no guests yet now discovers cleanly, where v2.3.1 showed a failed discovery.

1. Re-import `Proxmox_VE_Cluster_Backups`, `Proxmox_VE_Cluster_SDN`,
   `Proxmox_VE_Node_Network`, `Proxmox_VE_Node_Replication`,
   `Proxmox_VE_Cluster_GuestStatus`, `Proxmox_VE_Cluster_GuestPerformance`,
   `Proxmox_VE_Cluster_Snapshots` and `Proxmox_VE_Cluster_Topology`. The other modules, the
   NetScan and the dashboards are as in v2.3.1.

### Upgrading from v2.3.0

v2.3.1 is a fix release. Guest discovery and Snapshots now fail, rather than report no guests,
when Proxmox lists none: an under-privileged token sees an empty list, and an empty answer would
retire every guest instance. On a cluster that has no guests yet, Guest Status, Guest
Performance and Snapshots show a failed discovery until the first guest exists.

1. Re-import `Proxmox_VE_Cluster_GuestStatus`, `Proxmox_VE_Cluster_GuestPerformance`,
   `Proxmox_VE_Cluster_Snapshots` and `Proxmox_VE_Cluster_Topology`. The other modules, the
   NetScan and the dashboards are as in v2.3.0.

### Upgrading from v2.2.0

v2.3.0 adds one module, **Proxmox VE Troubleshooter**, which checks what the other modules need
to collect and reports each problem as an instance named with its fix (see Troubleshooting).
It also corrects every module's **Automatically Delete Instances** setting. Up to v2.2.0 the
setting was on for every module, against the suite's design, because LogicMonitor's import reads
the setting the other way round from how the modules wrote it. Now it is on only where an
instance going away is normal (see [Instances That Disappear](#instances-that-disappear)).
No property, the NetScan or the dashboards change.

1. Re-import every DataSource, and import `Proxmox_VE_Troubleshooter`. It applies to every
   cluster and node resource by category; nothing needs to be set.
2. A resource with no problems shows no troubleshooter instances. That is the healthy state.
3. Instances that up to v2.2.0 were deleted after 30 days come back with their history at the
   next discovery, if the object still exists and it is within the 30 days.

### Upgrading from v2.1.1

v2.2.0 adds guest pressure, ballooning and disk latency, draws bridges and guest VLANs on the
map, fixes SDN state on Proxmox VE 9, and stops child instances repeating their parent's alert.
Module names, properties, the NetScan and the dashboards are unchanged; four modules change.

- **Proxmox VE Cluster Guest Performance** gains, for every running guest, CPU, I/O and memory
  pressure (Proxmox's 10-second average); for VMs, the memory the balloon has reclaimed, guest
  swap rates and major page faults, and disk IOPS and read and write latency. None alerts.
  `MemoryHostMB` is now reported for VMs only: Proxmox sends 0 for every container, so a
  container's history for it stops rather than continuing at a false 0. The new figures cost one
  guest list per guest type per online node each poll.
- **Proxmox VE Cluster SDN** reads zone state in Proxmox VE 9's form as well as 8's. On 9 it
  reported no nodes and no `Healthy` before. A VNet now reports its zone's `NodesTotal` and
  `NodesOK` but no `Healthy`: only the zone alerts, once, when it fails on a node.
- **Proxmox VE Node Network** discovers, beside each bridge, the VLANs the node's guests use on it
  (`vmbr0 VLAN 30`, in the instance group `vlan`), which needs `VM.Audit` (part of `PVEAuditor`).
  A VLAN reports its bridge's `Active` and alerts on nothing: a bridge that goes down raises one
  alert. The **Ports** graph is now **Bridge Ports**; it and the Healthy line stay empty on a VLAN.
- **Proxmox VE Cluster Topology** draws node → bridge → guest, and node → bridge → VLAN → guest
  for a tagged NIC, beside node → SDN zone → VNet → guest. The map's **Connections** filter hides
  every Network edge at once; the bridge, VLAN, zone and VNet vertices stay on the map.

1. If a VNet has an open `Healthy` alert, wait for its zone to recover first. A VNet no longer
   reports `Healthy`, and LogicMonitor clears an alert only on a value back inside its threshold,
   never on No Data, so an alert open at the upgrade would stay open.
2. Re-import `Proxmox_VE_Cluster_GuestPerformance`, `Proxmox_VE_Cluster_SDN`,
   `Proxmox_VE_Node_Network` and `Proxmox_VE_Cluster_Topology`. The other modules, the NetScan
   and the dashboards are byte-for-byte as in v2.1.1.
3. Run Active Discovery for **Proxmox VE Node Network** on each node resource, **then** let the
   TopologySource run (saving it triggers a run), so the bridge and VLAN instances carry their
   vertex keys before the first run that draws them.
4. A VLAN no guest uses any more leaves the tree after 30 days, its history kept until then (see
   [Instances That Disappear](#instances-that-disappear)).

### Upgrading from v2.1.0

From v2.1.0 to v2.2.0: take step 1 under [Upgrading from v2.1.1](#upgrading-from-v211), then
the steps below with v2.2.0's modules, then steps 3 and 4 there.

v2.1.1 is a fix release. USB devices passed through to a guest no longer draw as a Storage edge
on the map, and a `403` from Proxmox now names the privilege it refused instead of
being reported as a rejected token. Module names, datapoints, properties and the dashboards are
unchanged.

1. Re-import every module, in the order given in [Import LogicModules](#import-logicmodules).
   Each module carries its own copy of the shared code that changed. Only
   `Proxmox_VE_Cluster_Topology` changes what is drawn; the others change only an error message.
2. Let the TopologySource run (saving it triggers a run). A guest's old USB Storage edge drops
   off the map.
3. Optionally paste the new NetScan script into each NetScan. It changes only the same error
   message, so a NetScan left at v2.1.0 keeps working.

USB devices passed through to a guest (a VM's **USB Device**, a container's `/dev/bus/usb`
device) are not drawn: Proxmox's guest config names only a vendor and product ID, so a Zigbee
or Z-Wave stick would show as a Storage edge from its node. A disk passed through by path, or a
host directory bind-mounted into a container, still draws as storage.

### Upgrading from v2.0.x

From v2.0.x to v2.2.0: take step 1 under [Upgrading from v2.1.1](#upgrading-from-v211), then
the steps below with v2.2.0's modules (they include its Active Discovery and topology run), then
step 4 there.

v2.1.0 draws the topology map the way LogicMonitor's vSphere suite does, with storage and
network layers under the nodes. Module names, datapoints, properties, the NetScan and the
dashboards are the same as in v2.0.x, so datapoint history and alert settings carry over. The
one visible change outside the map: `Proxmox_VE_Cluster_StorageCapacity` instance names now
say what kind of store each is (`local-lvm on Node-A (local, lvmthin, 363 GB)`), and their
history is kept. v2.0.0 to v2.0.1 changed only this page.

1. Import the four new modules: the DataSources `Proxmox_VE_Cluster_CephPools`,
   `Proxmox_VE_Cluster_SDN`, `Proxmox_VE_Node_Network` and `Proxmox_VE_Node_StoragePools`.
2. Re-import every other module, in the order given in
   [Import LogicModules](#import-logicmodules). Each module ships its own copy of the shared
   code the topology changes touched, so a module whose own script did not change still needs
   the new copy.
3. Run Active Discovery on each Proxmox resource, **then** let the TopologySource run (saving
   it triggers a run). The vertex keys must be on their instances before the first run that
   draws them.
4. Leave the NetScan and the dashboards as they are. Re-running the NetScan is not needed.

The cluster's map vertex has a new key, `proxmoxve--cluster--<cluster name>` in place of
`proxmoxve--<cluster name>`. After the upgrade the map draws the cluster under the new key,
with its edges to every node, and the vertex under the old key drops off the map.

## Troubleshooting

### The Troubleshooter Module

**Proxmox VE Troubleshooter** runs on every cluster and node resource. Every 15 minutes it checks,
in order, what the suite's modules need to collect, and stops at the first check that fails,
because the ones after it depend on it. Ping is the exception: nothing after it depends on it,
so a failed ping is reported and the checks go on. Each problem it finds is an instance under the resource,
named with its fix, and raises an error alert that carries the full finding. **A resource with no
troubleshooter instances has no problems.** The alert clears within five minutes of the fix; the
instance leaves the tree at the next check, and is deleted after 30 days.

| Instance | A problem when | The fix |
|---|---|---|
| `settings` | `pve.token.credential` is missing or is not the whole token string `user@realm!tokenid=secret` (a password does not work), or no API URL can be built | Set the property as the finding says |
| `address` | The resource's hostname does not resolve, or refuses or drops `pve.api.port` (8006) | Fix DNS or the hostname; open the port from the Collector |
| `ping` | The resource does not answer ping. LogicMonitor then treats it as dead and holds back its alerts | Allow ICMP from the Collector |
| `certificate` | The Collector does not trust the API's certificate, or it does not name the address. Not checked with `pve.api.insecure=true` | Install a certificate that names the address, or set `pve.api.insecure=true` |
| `token` | The API rejects the token (401) | Give the token a new secret and set it in `pve.token.credential` |
| `permissions` | The token lacks `Sys.Audit`, `VM.Audit`, `Datastore.Audit` or `SDN.Audit` on `/`, or holds them without propagation, or sees an empty cluster | Give the token `PVEAuditor` on `/`, propagated; with privilege separation, as the token's own ACL entry |
| `node` | On a node: `pve.node` names another node, corosync is not running, or the NetScan marked the node unreachable although the Collector reaches it | Set `pve.node`, start corosync, or run the NetScan |
| `failover-<address>` | On a resource with a cluster role: an address in `pve.api.hosts` does not answer | Bring the node back, or remove the address from `pve.api.hosts` |

Whether the alert shows the finding (through `##DSIDESCRIPTION##`) and the shape of Proxmox's
`/access/permissions` reply are *(not yet verified)* on a live portal; the instance's description
and its `auto.pve_finding` property always carry the finding.

- **No data on any module:** check the token from the Collector's host. On Linux:

  ```sh
  curl -k -H 'Authorization: PVEAPIToken=<token string>' https://<node>:8006/api2/json/cluster/resources
  ```

  On Windows, in PowerShell (type `curl.exe`: plain `curl` is PowerShell 5.1's alias for
  `Invoke-WebRequest`, and `cmd.exe` does not treat `'` as a quote):

  ```powershell
  curl.exe -k -H 'Authorization: PVEAPIToken=<token string>' https://<node>:8006/api2/json/cluster/resources
  ```

  Keep the single quotes: the token string holds a `!`, which Bash expands inside double quotes.
  - A `401` means the token ID or secret is wrong.
  - A `403` means the token is valid but lacks a privilege. Proxmox names it, for example
    `403 Permission check failed (/, Sys.Audit)`, and the module's error carries that text. Give
    the token's user the `PVEAuditor` role on `/`; with `--privsep 1` the token needs that ACL
    entry itself.
  - An empty `data` array means the token authenticates but has no permissions: with
    `--privsep 1` it needs its own ACL.
- **A node's modules do not apply:** its `pve.netscan.reachable` is `false`, and
  `pve.netscan.status` gives the reason. The node was online in the cluster, but the Collector
  could not reach its own address (a node that is merely down keeps its modules);
  open port 8006 from the Collector, or set `pve.netscan.node.hostname=name` if the cluster
  address is on a network the Collector cannot reach.
- **The cluster resource shows as down:** its hostname does not answer ping. With a VIP or proxy,
  check it; without one, there should be no cluster resource, so check `pve.api.url` and
  `pve.netscan.cluster.address`. While it is down LogicMonitor holds back its other alerts, and
  the standby node collects.
- **A resource you deleted comes back with its old properties:** LogicMonitor keeps deleted
  resources under Recently Deleted, and a NetScan run restores one from there. Purge it from
  Recently Deleted as well.
- **A node shows as down although its modules collect:** the Collector cannot ping it.
  LogicMonitor judges a resource up from ping, not from API data; allow ICMP from the Collector.
  The NetScan's output and the node's `pve.netscan.status` report a failed ping.
- **No guests were added at all:** the NetScan has no LogicMonitor API credentials, and its
  output says so. Add them (step 7).
- **A selected guest was not added:** the NetScan found no address for it (no live address, no
  static one in its configuration) and its run output says so. Give the guest an address, or set
  `pve.netscan.guests.unaddressed=true` to add it by name; it will then show as down.

| User | Permissions |
|---|---|
| The token's user | `PVEAuditor` on `/`, propagated |
| The token, with `--privsep 1` | `PVEAuditor` on `/`, propagated, as its own ACL |

**Note:** `/cluster/resources` returns an empty list, not an error, to a token without
permissions, so an under-privileged token looks like an empty cluster.

## NetScan Troubleshooting

The NetScan checks each resource against those already in the portal, by display name, hostname
and address, and on a match follows `hostname.source`:

- unset (default): the existing resource is left alone, and the match is reported
- `lm`: the existing resource is adopted; the NetScan's properties and groups are written to it
- `netscan`: a second resource is added, named `<name> - <hostname>`, using one more host unit

**Note:** adopting existing resources needs `hostname.source=lm` **and** the settings in step 10;
with `lm` alone, a run under the default criteria leaves them unchanged and reports nothing.

**Note:** editing and saving a resource in the portal detaches it from the NetScan that created
it (the audit log shows its scan configuration reset). The NetScan still updates it. The audit
log records only some of a NetScan's updates, so check the resource itself to see whether a run
changed it.

**Note:** a NetScan property can't be saved empty ("Properties cannot be empty"). To leave one
at its default, such as `pve.netscan.guests.filter.vmid` for every guest, delete the property
rather than clear its value; the NetScan treats a missing property as unset.

The NetScan never deletes a resource. A guest or node removed from Proxmox keeps its resource,
and the run reports it.

When a guest's address changes, the NetScan keeps the one resource under its old hostname, sets
`pve.netscan.status` to `address changed: was <old>, now <new>`, and says so in the run's report.
It cannot change a hostname itself, so change it under **Manage** to follow. A NetScan never changes a hostname
by sending a new one: LogicMonitor would create a second resource instead, named
`<hostname>#<collector id>` if the display name is taken.

**The report on the cluster's active resource** (`pve.netscan.report`, also written to the run's
log) says what each run did: which resources carry the cluster role, collisions, orphans, address
changes, and refusals. Two lines concern the LogicMonitor API token:

- **"no LogicMonitor API credentials: duplicate checking is off"** — `lmaccess.id` or
  `lmaccess.key` is not set.
- **"LogicMonitor API unavailable, no duplicate checking: …"** — the token was refused or the
  call failed; the message gives the reason. A role without **View** on **Resources** is the usual
  cause.

## Proxmox VE LogicModules in Package

Proxmox VE modules are organised by the resource role they apply to. This is the first release,
so no module replaces another.

**Proxmox VE Cluster modules** (apply where `pve.cluster.role` is `active` or `standby`; only the active resource collects)

| Display Name | Type | Description | Replaces |
|---|---|---|---|
| Proxmox VE Cluster Status | DataSource | Quorum, HA manager health, migrations, nodes online and a cluster health score | — |
| Proxmox VE Cluster Nodes | DataSource | Each node's online state, seen from the cluster | — |
| Proxmox VE Cluster Guest Status | DataSource | Power state, lock and HA state of every VM and container | — |
| Proxmox VE Cluster Guest Performance | DataSource | CPU, memory, network and disk of every VM and container; CPU/IO/memory pressure, with alerts on sustained CPU contention and memory thrashing; for VMs, ballooning, swap-in, disk IOPS and disk latency | — |
| Proxmox VE Cluster VM Health | DataSource | Each VM that QEMU has paused while Proxmox reads it as running (a full or failed storage), and each VM whose guest agent stops answering (a hung guest) | — |
| Proxmox VE Cluster Storage Capacity | DataSource | Capacity and availability of every storage, per node | — |
| Proxmox VE Cluster Ceph | DataSource | Ceph health, placement groups and capacity | — |
| Proxmox VE Cluster Ceph OSD | DataSource | Each OSD's state, fill level and latency | — |
| Proxmox VE Cluster Ceph Pools | DataSource | Each Ceph pool's use and replication | — |
| Proxmox VE Cluster SDN | DataSource | State of each SDN zone and VNet across the nodes | — |
| Proxmox VE Cluster Backups | DataSource | Backup jobs, each guest's last backup, unprotected guests | — |
| Proxmox VE Cluster Snapshots | DataSource | Snapshot count and age per guest, hourly | — |
| `Proxmox_VE_Cluster_Topology` | TopologySource | The map, modelled on vSphere's: cluster, nodes and guests; storage (stores, pools, disks, Ceph); networking (bridges, guest VLANs, SDN zones and VNets, all Network edges the map's Connections filter can hide); storage, networking and the cluster tree seen on a lab cluster | — |

**Proxmox VE Node modules** (apply on `ProxmoxVE_Node`)

| Display Name | Type | Description | Replaces |
|---|---|---|---|
| Proxmox VE Node Detail | DataSource | CPU, memory, uptime, load, swap, root filesystem, CPU/IO/memory pressure, and the node's view of quorum | — |
| Proxmox VE Node Disks | DataSource | SMART health, size and SSD wear of each disk, and a disk that is no longer there | — |
| Proxmox VE Node Services | DataSource | State of the Proxmox-managed systemd services | — |
| Proxmox VE Node Certificates | DataSource | TLS certificate expiry | — |
| Proxmox VE Node Subscription | DataSource | Subscription status and renewal date; whether the node needs a reboot to run its newest kernel; how many Proxmox package updates are waiting | — |
| Proxmox VE Node Replication | DataSource | Replication job failures, staleness and duration | — |
| Proxmox VE Node Storage Pools | DataSource | Size, use and health of ZFS pools, LVM volume groups and thin pools | — |
| Proxmox VE Node Network | DataSource | State of the bridges guests attach to, and of the VLANs guests use on them | — |

**Proxmox VE Troubleshooter** (applies on `ProxmoxVE_Cluster` and `ProxmoxVE_Node`, whatever the cluster role)

| Display Name | Type | Description | Replaces |
|---|---|---|---|
| Proxmox VE Troubleshooter | DataSource | What stops collection -- settings, address, ping, certificate, token, permissions, node identity, failover addresses -- one instance per problem, named with its fix | — |

**Proxmox VE PropertySources and NetScan**

| Display Name | Type | Description | Replaces |
|---|---|---|---|
| `Proxmox_VE_Info` | PropertySource | Proxmox version, release and cluster membership; sets no category | — |
| `addERI_Proxmox_VE_Node` | PropertySource (ERI) | The node's topology identity | — |
| `addERI_Proxmox_VE_GuestResource` | PropertySource (ERI) | Links a guest resource to its vertex on the map | — |
| `Proxmox_VE_NetScan.groovy` | Enhanced Script NetScan | Creates, groups and updates the cluster, node and guest resources | — |

**Note:** Guest resources get no Proxmox API module. Monitor what runs inside a guest (SNMP, WMI,
SSH) as for any resource; its hypervisor metrics stay with the cluster modules.

**Proxmox VE Dashboards** (from the repository; see
[Getting the NetScan Script and Dashboards](#getting-the-netscan-script-and-dashboards))

| Dashboard | File | Shows |
|---|---|---|
| Proxmox VE | `Proxmox_VE.json` | Cluster health and capacity; node and guest CPU, memory, storage and network; top-10 guests, nodes and storage; guest state; alerts |
| Proxmox VE Infrastructure | `Proxmox_VE_Infrastructure.json` | Cluster health; Ceph and physical disks; backups and replication; certificate and subscription expiry; node services; alerts |

Both dashboards scope to the token `defaultResourceGroup`, `*` by default: set it to a cluster's
group to show one cluster. Import them in **Dashboards > Add > From File**.

**Recommendation:** Import the dashboards after the modules, so every widget finds its module.
