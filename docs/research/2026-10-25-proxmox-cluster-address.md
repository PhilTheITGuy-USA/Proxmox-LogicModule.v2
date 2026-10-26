# A single address for a Proxmox VE cluster: what Proxmox and its community say

Researched 2026-10-25, after spike S2 showed that a cluster resource whose hostname does not
resolve is always shown dead: data from script DataSources never resets HostStatus's
`idleInterval` ([LM HostStatus doc](https://www.logicmonitor.com/support/hoststatus-datasource);
S2, `S2_KeepAlive` 2026-10-25). The question: can v2 expect a cluster to have a VIP or a DNS name
of its own?

Claims are tagged **[doc]** (Proxmox's own documentation or bug tracker), **[community]**
(forum or mailing-list posts, not Proxmox staff unless stated) or **[inferred]**.

## 1. Proxmox ships no cluster address

- **Any node manages the whole cluster, by design.** The admin guide (9.2.13, 2026-09-24),
  §1.1 Central Management: "Every node in a Proxmox VE cluster can perform all management
  tasks", and "There is no single-point-of-failure central management server—any node can manage
  the entire cluster." [doc] ([admin guide](https://pve.proxmox.com/pve-docs/pve-admin-guide.html))
- **No VIP, floating IP or cluster hostname feature** appears in the part of the guide read (its
  first eighth, the overview) nor in a search of pve.proxmox.com, which returned only forum
  threads [doc, partial]; the forum's answer to "is there one" is "there is no built-in supported mechanism"
  ([forum, 2023](https://forum.proxmox.com/threads/vip-ip-for-cluster-to-connect-with-proxmox-api.128447/))
  [community]. HA manager resources are guests, not addresses [inferred].
- **The feature request is open.** Bugzilla #4762, "proxmox VIP IP for quorated node for API
  usage", reported 2023-06-06, status NEW, no resolution, no Proxmox staff reply; last changed
  2024-08-09 ([bug 4762](https://bugzilla.proxmox.com/show_bug.cgi?id=4762)) [doc]. The thread
  shows why it is hard: the address must leave a node that loses quorum, which keepalived check
  scripts did not manage reliably.

## 2. What people build instead

| Approach | How | What the community reports |
|---|---|---|
| **keepalived (VRRP) VIP** | `apt install keepalived` on every node, one MASTER and BACKUPs, a free static IP; pveproxy listens on 8006 on every address, the VIP included | the most described approach; "VRRP and keepalived work beautifully", "serves us very well for redundancy of the web GUI"; 15–30 minutes to set up; **not endorsed or supported by the Proxmox team**; one report that the VIP should sit outside the nodes' subnet or migrations to that node can fail ([forum, 2025](https://forum.proxmox.com/threads/virtual-ip.166424/), [forum](https://forum.proxmox.com/threads/ha-for-proxmox-cluster-management-portal.142926/), [pve-user](https://lore.proxmox.com/all/mailman.50.1741981360.416.pve-user@lists.proxmox.com)) [community] |
| **Reverse proxy / load balancer** | HAProxy, nginx, Caddy or a commercial balancer, on the router or in a VM, in front of all nodes' 8006 | called "the more common approach" and "the simplest" by several users; keeps extra software off the hypervisors; a shared certificate covers the proxy name ([forum, 2025](https://forum.proxmox.com/threads/virtual-ip.166424/), [forum](https://forum.proxmox.com/threads/what-are-the-best-practices-for-certificate-generation.140240/)) [community] |
| **Round-robin DNS** | one name, one A record per node | can hand out a down node's address ("a 30% chance" in a 3-node cluster); consoles failed through it on 7.1; Veeam's staff say it is **not supported** for discovering a Proxmox cluster and leads to incomplete inventory ([forum, 2025](https://forum.proxmox.com/threads/virtual-ip.166424/), [forum](https://forum.proxmox.com/goto/post?id=551187), [Veeam](https://forums.veeam.com/proxmox-ve-f79/veeam-do-not-shows-nodes-of-proxmox-ve-t102135.html)) [community] |
| **Nothing** | use another node when one is down | the default, and a view some users argue for [community] |

**Certificates:** each node's pveproxy uses its own certificate, signed by the cluster CA, at the
node-local `/etc/pve/local/pveproxy-ssl.pem`; a shared name works only with a certificate that
names it on every node, or on a proxy in front [community; path per the certificate docs].

## 3. What this means for v2 [inferred]

1. **v2 cannot require a cluster address.** Proxmox has none, its developers have not built one
   since 2023, and the workarounds are add-ons the Proxmox team does not support. Many clusters,
   the lab's among them, will have none.
2. **Where a cluster has one, use it.** A keepalived VIP or a load balancer's name in
   `pve.api.url` becomes the cluster resource's hostname: it resolves and answers ping on the
   node or proxy holding it, so HostStatus works as it does for vCenter. The install guide names
   both as the user's choice, with the community's caveats, not as a requirement.
3. **Round-robin DNS is not recommended** for the cluster resource's hostname: its ping can land
   on a down node, which would flap the resource's status. `pve.api.hosts` failover already does
   for the API what round-robin would.
4. **The no-address case needs its own answer,** since it is the common one. Candidates, none yet
   verified:
   - a Ping Multi DataSource on the cluster resource pinging every node's address. One community
     report says HostStatus counts its replies when the pinged addresses are also in the resource's
     `system.ips` ([LM community](https://community.logicmonitor.com/discussions/product-discussions/user-defined-host-dead-status/16529)),
     which a NetScan may or may not be able to set;
   - accepting the dead state and turning HostStatus alerting off on the cluster resource, which
     LogicMonitor discourages and which is acceptable only if a dead resource still raises its
     other modules' alerts (to test on `Labcluster` while it is dead).

## 4. Outcome, tested on the lab the same day

Both candidates of §3 item 4 failed in spike S2, and a third was ruled out:

- **`PingMulti-` on the node addresses:** 0% loss to both nodes for 50 minutes, and HostStatus's
  `heartbeat` never moved. Replies from other addresses do not count, as the community report's
  `system.ips` condition implied [S2].
- **Accepting the dead state:** ruled out by LogicMonitor's documentation, not by test. A dead
  resource raises no new alerts but HostStatus, and suppresses the notifications of the ones it
  has ([HostStatus](https://www.logicmonitor.com/support/hoststatus-datasource)) [doc].
- **A loopback hostname** (`127.0.0.2`) answers, but makes the resource a copy of the Collector's
  own host: its Collector modules and, over WMI, its Hyper-V modules applied [S2].

So the design (main spec §3.1, user 2026-10-25) uses a cluster resource only where the cluster has
an address of its own, which §2's methods supply, and otherwise puts the cluster role on the two
nodes with the lowest node IDs, one active and one standing by.
