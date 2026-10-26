# Proxmox VE Monitoring

> **Draft, 2026-10-25.** v2 is designed but not built. This page follows the layout of
> LogicMonitor's own [VMware vSphere Monitoring](https://www.logicmonitor.com/support/vmware-vsphere-monitoring)
> article and describes the suite as the specs define it
> (`docs/superpowers/specs/2026-10-21-proxmox-v2-architecture-design.md` and
> `2026-10-25-netscan-design.md`). Anything marked *(not yet verified)* is design intent that no
> lab run has confirmed. Module display names are proposed and fixed at build.

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
- Proxmox VE 9.x standalone hosts *(not yet verified)*
- Proxmox VE 8.x *(not yet verified; see Setup Requirements for the guest-agent privilege)*

Designed for up to 10 clusters of 16 nodes and 1,000 guests each.

## Setup Requirements

- The Proxmox VE LogicModules (see [LogicModules in Package](#proxmox-ve-logicmodules-in-package))
- A Collector at version 32.400 or later, which the Enhanced Script NetScan requires
- HTTPS access from that Collector to port 8006 on **every** node, not only the API address:
  each node's modules ask that node directly
- **Ping (ICMP) from that Collector to every node**, and to the cluster address if you use one.
  The modules collect through the API, but LogicMonitor decides whether a resource is up from
  ping: a node it cannot ping shows as down, and its alerts are held back
- One Proxmox VE API token per cluster, with the permissions below
- Optionally, an address for the cluster as a whole (see
  [Giving the Cluster One Address](#giving-the-cluster-one-address-optional))

| Category | User Permissions Required |
|---|---|
| Cluster, node, storage, Ceph, backup and guest metrics | `PVEAuditor` role on `/`, propagated |
| A guest's live address (NetScan, Proxmox VE 9) | `VM.GuestAgent.Audit`, which `PVEAuditor` includes |
| A guest's live address (NetScan, Proxmox VE 8) | `VM.Monitor`, which `PVEAuditor` does not include *(not yet verified)* |

**Note:** Without the guest-agent privilege the NetScan still runs: it uses the address in the
guest's configuration, or the guest's name, as the hostname, and reports that it did.

### Creating an API Token for Proxmox VE

On any node of the cluster, as root:

```sh
pveum user add monitor@pve --comment "LogicMonitor"
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
      Pool - <pool>/            guests grouped by Proxmox pool
  Host - <node>/                a standalone host instead of Cluster - …
```

Guests are never grouped by node, so a migration never moves a resource; the node is the
`pve.node` property, for dynamic groups. Run one NetScan per cluster.

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
   | LogicMonitor API ID and key | For duplicate checking against existing resources; names fixed at build | — | Recommended |
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
   | `pve.netscan.guests.unaddressed` | `true` also adds selected guests with no address the NetScan can read; they show as down | `true` | `false` |
   | `pve.netscan.guests.groupBy` | `pool` or `none` | `none` | `pool` |
   | `skip.device.dedupe` | `true` turns duplicate checking off | `true` | `false` |
   | `hostname.source` | On a duplicate: empty skips it and reports it; `lm` adopts the existing resource; `netscan` adds a second one | `lm` | empty |
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

9. Under **Script**, select **Embed a Groovy script** and paste the NetScan script shipped with
   the package (`Proxmox_VE_NetScan.groovy`).

   **Warning:** Do not edit the script. Change its behaviour only through the properties above.

10. Leave **Ignore system.ips when checking existing Resources for duplicates** off, and
    **Exclude duplicate IP addresses** at **Matching any Monitored Resources**.
11. In **Schedule**, select **Run this NetScan on a schedule**. Hourly is recommended; a run with
    guests off costs two API calls plus one per node.
12. Select **Save** or **Save and Run**.

**Recommendation:** If the cluster has an address of its own (see
[Giving the Cluster One Address](#giving-the-cluster-one-address-optional)), put it in
`pve.api.url`. The NetScan's output says which arrangement it chose, for example "no cluster
address: active pve1, standby pve2".

A guest resource's hostname is the guest's live IP address, read from the container's interfaces
or the VM's guest agent on the interface that carries `net0`'s MAC address; else the static
address in its configuration. A guest with neither is not added, and the run reports it: its name
would not answer ping, so it would show as down. It is never replaced by a DNS name, so that
LogicMonitor's duplicate-IP check recognises a guest you already monitor.

## Manually Assigning Properties to Resources

If you add resources through the Advanced NetScan, it sets every property below and keeps it
current. Add resources manually only where a NetScan cannot run, and give each the category of
its role in `system.categories`.

### Cluster Role

The resources that carry the cluster modules: a cluster resource and a standby node, or two
nodes. A node carrying the role keeps its node properties too.

| Property | Value | Example values | Required |
|---|---|---|---|
| `system.categories` | `ProxmoxVE_Cluster` on a cluster resource; `ProxmoxVE_Node,ProxmoxVE_Cluster` on a node (and on a standalone host) | `ProxmoxVE_Cluster` | Yes |
| `pve.cluster.role` | `active` or `standby` | `standby` | Yes, on a cluster |
| `pve.cluster.active` | On the standby: the active node's name, or `address:<host>` for a cluster resource | `address:pve-vip.example.com` | Yes, on the standby |
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

### Import LogicModules

Import every module listed under [LogicModules in Package](#proxmox-ve-logicmodules-in-package),
then the dashboards. The modules apply by category, so nothing else needs to be set on the
resources the NetScan creates.

## Troubleshooting

- **No data on any module:** check the token from the Collector's host:
  `curl -k -H 'Authorization: PVEAPIToken=<token string>' https://<node>:8006/api2/json/cluster/resources`.
  - A `401` means the token ID or secret is wrong.
  - An empty `data` array means the token authenticates but has no permissions: with
    `--privsep 1` it needs its own ACL.
- **A node's modules do not apply:** its `pve.netscan.reachable` is `false`, and
  `pve.netscan.status` gives the reason. The Collector could not reach that node's own address;
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

- empty (default): the existing resource is left alone, and the match is reported
- `lm`: the existing resource is adopted; the NetScan's properties and groups are written to it
- `netscan`: a second resource is added, named `<name> - <hostname>`, using one more host unit

**Note:** LogicMonitor's own duplicate-IP exclusion (step 10) also skips any resource whose IP
already exists, independently of the script. It skips; it does not update. Adopting existing
resources needs `hostname.source=lm` *(not yet verified)*.

The NetScan never deletes a resource. A guest or node removed from Proxmox keeps its resource,
and the run reports it.

## Proxmox VE LogicModules in Package

Proxmox VE modules are organised by the resource role they apply to.

**Proxmox VE Cluster modules** (apply on `ProxmoxVE_Cluster`; only the active resource collects)

| Display Name | Type | Description | Replaces |
|---|---|---|---|
| Proxmox VE Cluster Status | DataSource | Quorum, HA manager health, migrations, nodes online and a cluster health score | `Proxmox_VE_Cluster` (v1) |
| Proxmox VE Cluster Nodes | DataSource | Each node's online state, seen from the cluster | `Proxmox_VE_Nodes` (v1), in part |
| Proxmox VE Guest Status | DataSource | Power state, lock and HA state of every VM and container | `Proxmox_VE_GuestStatus` (v1) |
| Proxmox VE Guest Performance | DataSource | CPU, memory, network and disk of every VM and container | `Proxmox_VE_GuestPerformance` (v1) |
| Proxmox VE Storage Capacity | DataSource | Capacity and availability of every storage, per node | `Proxmox_VE_StorageCapacity` (v1) |
| Proxmox VE Ceph | DataSource | Ceph health, placement groups and capacity | `Proxmox_VE_Ceph` (v1) |
| Proxmox VE Ceph OSD | DataSource | Each OSD's state, fill level and latency | `Proxmox_VE_CephOSD` (v1) |
| Proxmox VE Backups | DataSource | Backup jobs, each guest's last backup, unprotected guests | `Proxmox_VE_Backups` (v1) |
| Proxmox VE Snapshots | DataSource | Snapshot count and age per guest, hourly | `Proxmox_VE_Snapshots` (v1) |
| Proxmox VE Topology | TopologySource | Cluster to node, node to guest | `Proxmox_VE_Topology` (v1) |

**Proxmox VE Node modules** (apply on `ProxmoxVE_Node`)

| Display Name | Type | Description | Replaces |
|---|---|---|---|
| Proxmox VE Node Detail | DataSource | CPU, memory, uptime, load, swap, root filesystem, CPU/IO/memory pressure, and the node's view of quorum | `Proxmox_VE_NodeDetail`, `Proxmox_VE_Nodes` (v1), in part |
| Proxmox VE Node Disks | DataSource | SMART health, size and SSD wear of each disk | `Proxmox_VE_Disks` (v1) |
| Proxmox VE Node Services | DataSource | State of the Proxmox-managed systemd services | `Proxmox_VE_NodeServices` (v1) |
| Proxmox VE Node Certificates | DataSource | TLS certificate expiry | `Proxmox_VE_Certificates` (v1) |
| Proxmox VE Node Subscription | DataSource | Subscription status and renewal date | `Proxmox_VE_Subscription` (v1) |
| Proxmox VE Node Replication | DataSource | Replication job failures, staleness and duration | `Proxmox_VE_Replication` (v1) |

**Proxmox VE PropertySources and NetScan**

| Display Name | Type | Description | Replaces |
|---|---|---|---|
| `Proxmox_VE_Info` | PropertySource | Proxmox version, release and cluster membership; sets no category | `addCategory_Proxmox_VE` (v1), in part |
| `addERI_Proxmox_VE_Node` | PropertySource (ERI) | The node's topology identity | `addERI_Proxmox_VE` (v1) |
| `addERI_Proxmox_VE_GuestResource` | PropertySource (ERI) | Links a guest resource to its vertex on the map | `addERI_Proxmox_VE_Guest` (v1) |
| `Proxmox_VE_NetScan.groovy` | Enhanced Script NetScan | Creates, groups and updates the cluster, node and guest resources | v1's manual setup |

**Note:** Guest resources get no Proxmox API module. Monitor what runs inside a guest (SNMP, WMI,
SSH) as for any resource; its hypervisor metrics stay with the cluster modules.

**Recommendation:** Import the dashboards after the modules, so every widget finds its module.

## Migration from Legacy Modules

v2 is a separate package: it needs no v1 module installed and changes none. Its modules and its
token property, `pve.token.credential`, have names v1 does not use, so v1 and v2 can run side by
side while you move over, and v1 never applies itself to a resource v2 creates.

Moving from v1 does not convert anything. Guest instances keep their identifiers, but v1's
history stays on v1's resource and does not move to the cluster resource. v1 remains readable
until you delete it.

**Recommendation:**

1. Install v2 and run its NetScan with `hostname.source=lm`, so the node resources v1 already has
   are adopted rather than duplicated *(not yet verified)*.
2. Check the new dashboards.
3. Delete `pve.api.token.credential` from the adopted nodes. That property is what applies v1.
4. Disable, then delete, v1's modules.

Run both only briefly: both poll the same API.
