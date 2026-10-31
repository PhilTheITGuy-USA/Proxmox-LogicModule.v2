# Bridges and VLANs on the Map Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Draw node → bridge → guest and node → bridge → VLAN → guest as `Network` edges, with `Node_Network` carrying the bridge and VLAN vertices.

**Architecture:** Shared preamble helpers (`scripts/lib/pve_common.groovy`) parse guest NICs and build the bridge and VLAN keys; `pveTopoPlan` draws the edges from them, and `Node_Network`'s discovery stamps the same keys from its own node's guest configs. `Node_Network`'s collection reports VLAN instances through `datasourceinstanceProps`. The harness checks plan and carriers agree key for key.

**Tech Stack:** Groovy 4 / JDK 17 (Collector), Python build (`build/build.py`), Docker harness (`tests/harness.groovy`, mock API `tests/fixtures/pve_api.json`, scale fixture `tests/fixtures/scale.groovy`).

**Spec:** `docs/superpowers/specs/2026-10-21-proxmox-v2-architecture-design.md` §3.4, the paragraph "Bridges and VLANs back on the map (amended 2026-10-30)" and the keep-instances sentence after it.

## Global Constraints

- Branch `topo-bridges-vlans`; merge only when the user says so. Run `python ../lab-scrub/check.py` before every push; lab names stay out of tracked files (stand-ins: `Node-A`, `Node-B`, `Relay01`, `Labcluster`).
- Every Network edge type is the string `Network`. Both new vertex types are `VirtualSwitch`.
- Bridge key: `pveTopoVertexKey(cluster, ['net', node, bridge])`. VLAN key: `pveTopoVertexKey(cluster, ['net', node, bridge, 'vlan' + tag])`.
- VLAN wildvalue `<bridge>-vlan<N>`; instance property `pve_iface_type` = `vlan` (the module's `instanceGroupProperty` is `auto.pve_iface_type`).
- A NIC whose `bridge=` names an SDN VNet joins the VNet only (no bridge or VLAN vertex). A NIC on a bridge the node's `/network` does not list draws nothing. `trunks=` is ignored. Templates are left out, as `pveGuestList` leaves them out.
- SDN zones stay under the node (no bridge → zone edge).
- Unused VLAN instances are kept (`deleteInactiveInstances` stays off), as every module in the suite.
- Most sources are CRLF: edit with the Edit tool, never `sed -i`. Script length cap 49,135 characters (`check_script_lengths`); `technicalNotes` at most 4,096.
- Verification commands, from the repo root, after `python build/build.py`:
  - `docker compose -f tests/docker-compose.yml run --rm compile`
  - `docker compose -f tests/docker-compose.yml run --rm tests > "$TMP/harness.txt" 2>&1; grep FAILED "$TMP/harness.txt"` (expect no output, and `PASSED  all N checks`)
  - `python ../logicmonitor-modules-skill/skill/scripts/lm_lint_module.py dist` (0 errors, the baseline 4 warnings)
  - `python ../logicmonitor-modules-skill/skill/scripts/lm_dashboards.py check dist/dashboards/*.json --modules modules`

## Review Focus

1. **A VNet listed in a node's `/network` as a bridge.** If Proxmox lists applied VNets there, the plan would draw node → VNet-as-bridge beside the SDN path. Expected: a name that is a VNet is never a bridge vertex. Test in Task 1 (`topo-net-vnet-not-bridge`) and Task 2 (no bridge instance named after a VNet carries a key).
2. **`datasourceinstanceProps` shape in the real Collector** (map of instance → properties, `wildvalue` present, properties with or without `auto.`). Expected: VLANs report whichever shape arrives; absence leaves them empty and exits 0. Test in Task 3; proved on the lab in Task 5.
3. **Guest NIC strings with unusual order or extra keys** (`virtio=MAC,bridge=vmbr0,tag=30,firewall=1`; `name=eth1,bridge=vmbr0,hwaddr=…,tag=30,type=veth`; `tag=` with no `bridge=`). Expected: bridge and tag parsed regardless of order; no `bridge=` → no edge. Test in Task 1 (`guest-nics`).
4. **Plan and carrier disagreeing on which guests count** (stopped guests, templates, guests on an offline node). Expected: both include stopped guests and exclude templates; the carrier's VLAN set for a node equals the plan's VLAN vertices for that node. Test in Task 2 (`topo-carriers` agreement over a fixture with a stopped guest and a template).
5. **A guest config read failing in discovery** (403 on one guest, budget reached). Expected: that guest's VLANs are skipped with one stderr line; discovery still exits 0 with the bridges and the other VLANs, so an outage of one guest never drops instances. Test in Task 2 (`net-vlan-config-failure`).

---

### Task 1: NIC parsing, keys and the plan's Network edges

**Files:**
- Modify: `scripts/lib/pve_common.groovy` (helpers near `pveNetBridges` ~line 963; `pveTopoInput` ~1208; `pveTopoPlan` network section ~1340 and guest NIC loop ~1370)
- Modify: `scripts/Proxmox_VE_Cluster_Topology.topo.groovy` (header comment line 8 only)
- Test: `tests/harness.groovy` (topology block ~2030-2100), `tests/fixtures/pve_api.json` only through `topoFixtures` overrides

**Interfaces:**
- Produces:
  - `pveGuestNics(Map config) -> List<Map>`: one `[slot: 'net0', bridge: String, tag: Integer|null]` per `netN` that has `bridge=`, sorted by slot; `tag` parsed from `tag=` as an integer 1-4094, else null.
  - `pveBridgeKey(String cluster, String node, String bridge) -> String`
  - `pveVlanKey(String cluster, String node, String bridge, int tag) -> String`
  - `pveNodeBridgeNames(List networkRows, Collection vnetNames) -> Set<String>`: names from `pveNetBridges(rows)` minus VNet names.
  - `pveTopoInput()` gains `perNode[node].network` (from `/nodes/<node>/network`, read first in the per-node block).

- [ ] **Step 1: Write the failing tests.** In the topology block, extend `topoFixtures` with: `/nodes/pve1/network` already present (vmbr0 VLAN-aware, vmbr1, `vmbr0.20` type vlan, bond0); add a `/nodes/pve2/network` with `vmbr0` (type `bridge`) and `ovs0` (type `OVSBridge`), and add `labnet` as type `bridge` to pve1's network list (Review Focus 1); make both nodes online in `topoFixtures` if they are not. Guests (add to `/cluster/resources?type=vm` and their configs):
  - web (existing qemu/101 on pve1, `net0 bridge=vmbr0` untagged) — add `net1: 'virtio=BC:24:11:0E:00:12,bridge=vmbr0,firewall=1'` (scenario 1: still one bridge → guest edge) and `net2: 'virtio=BC:24:11:0E:00:22,bridge=vmbr0,tag=10'`, `net3: 'virtio=BC:24:11:0E:00:32,firewall=1,tag=20,bridge=vmbr0'` (scenarios 2, 3, Review Focus 3).
  - media (lxc/103 on pve1, existing `net0 bridge=labnet`) — add `net1: 'name=eth1,bridge=labnet,tag=5,type=veth'` (VNet NIC with a tag: VNet edge only) and `net2: 'name=eth2,bridge=vmbr1,hwaddr=BC:24:11:0E:00:13,trunks=10;20,type=veth'` (scenario 4 and 6: vmbr1 → guest, no VLAN).
  - lxc/201 on pve2, `net0: 'name=eth0,bridge=vmbr0,hwaddr=BC:24:11:0E:00:04,tag=10,type=veth'` (scenario 7: VLAN 10 on pve2's vmbr0 is its own vertex), `net1: 'name=eth1,bridge=ovs0,hwaddr=BC:24:11:0E:00:14,tag=30,type=veth'` (scenario 5, OVS), `net2: 'name=eth2,bridge=vmbr9,hwaddr=BC:24:11:0E:00:24,type=veth'` (scenario 8: no vmbr9 → nothing), `net3: 'name=eth3,tag=40,type=veth'` (no bridge → nothing).
  - qemu/202 on pve2, `status: 'stopped'`, `net0: 'virtio=BC:24:11:0E:00:05,bridge=vmbr0,tag=10'` (scenario 11: drawn), and qemu/203 `template: 1` with `net0: 'virtio=BC:24:11:0E:00:06,bridge=vmbr0,tag=99'` (not drawn).
  - A second SDN zone `solo` (type `simple`, `nodes: 'pve2'`) with VNet `solonet` (scenario 10: only pve2 → solo).

  Move these from the "unexpected" list to the expected list: `['Network', topoN1, topoKey('net','pve1','vmbr0')]`, `['Network', topoKey('net','pve1','vmbr0'), topoWeb]`. Add expected:
  ```groovy
  ['Network', topoN1, topoKey('net','pve1','vmbr1')],
  ['Network', topoKey('net','pve1','vmbr0'), topoKey('net','pve1','vmbr0','vlan10')], ['Network', topoKey('net','pve1','vmbr0','vlan10'), topoWeb],
  ['Network', topoKey('net','pve1','vmbr0'), topoKey('net','pve1','vmbr0','vlan20')], ['Network', topoKey('net','pve1','vmbr0','vlan20'), topoWeb],
  ['Network', topoKey('net','pve1','vmbr1'), topoMedia],
  ['Network', topoN2, topoKey('net','pve2','vmbr0')], ['Network', topoN2, topoKey('net','pve2','ovs0')],
  ['Network', topoKey('net','pve2','vmbr0'), topoKey('net','pve2','vmbr0','vlan10')],
  ['Network', topoKey('net','pve2','vmbr0','vlan10'), 'bc:24:11:0e:00:04'], ['Network', topoKey('net','pve2','vmbr0','vlan10'), 'bc:24:11:0e:00:05'],
  ['Network', topoKey('net','pve2','ovs0'), topoKey('net','pve2','ovs0','vlan30')], ['Network', topoKey('net','pve2','ovs0','vlan30'), 'bc:24:11:0e:00:04'],
  ['Network', topoN2, topoKey('sdn','solo')], ['Network', topoKey('sdn','solo'), topoKey('sdn','solo','solonet')],
  ```
  Keep unexpected: `bond0`, `eno*`, `lo`, `vmbr0-20`/`vmbr0.20`, `topoKey('net','pve1','vmbr0'), topoKey('sdn','lab')`, `topoKey('net','pve1','labnet')` in any edge (`topo-net-vnet-not-bridge`). Add unexpected: `['Network', topoN1, topoKey('sdn','solo')]`; any key containing `vmbr9`, `vlan99`, `vlan40`, `vlan5`; `topoKey('net','pve1','vmbr1','vlan10')`, `topoKey('net','pve1','vmbr1','vlan20')`; any `vlan` key under `labnet`. Replace the comment "Network: SDN only" with the spec's rule.

  Add a `guest-nics` check calling `pveGuestNics` through `runSource(preamble + ...)` on: `[net0: 'virtio=AA,bridge=vmbr0,tag=30,firewall=1', net1: 'name=eth1,bridge=vmbr1,hwaddr=BB,type=veth', net2: 'name=eth2,tag=7,type=veth', net10: 'virtio=CC,bridge=vmbr0,tag=4095']` → `[[slot:'net0',bridge:'vmbr0',tag:30],[slot:'net1',bridge:'vmbr1',tag:null],[slot:'net10',bridge:'vmbr0',tag:null]]` (4095 out of range → untagged; slots sorted numerically).

- [ ] **Step 2: Run the harness; expect `topo-plan-edges` and `guest-nics` FAILED lines** (helpers undefined, bridge edges missing).

- [ ] **Step 3: Implement** the four helpers (Interfaces above) beside `pveNetBridges`, rewrite its comment to the spec's rule; in `pveTopoInput` add `read('network', '/network')` first in the per-node block; in `pveTopoPlan` compute `bridgesOf[node] = pveNodeBridgeNames(perNode[node]?.network, vnets.keySet())`, draw `node → bridge` for each, keep the SDN block unchanged, and replace the guest NIC loop with: VNet name → VNet edge; else bridge in `bridgesOf[node]` → `tag ? bridge → VLAN, VLAN → guest : bridge → guest`; else nothing. Update the topology script's header line 8 to `node -> bridge [-> VLAN] -> guest, node -> SDN zone -> VNet -> guest (ports and bonds kept off the map)` and the preamble's topology-model comment (~line 895) to match.

- [ ] **Step 4: Rebuild and run the harness; expect no FAILED lines.** If `topo-plan-requests` fails because a node's `/network` has no fixture, add the fixture, not an exception.

- [ ] **Step 5: Commit** `Topology: bridges and guest VLANs drawn as Network edges (A§3.4 amended 2026-10-30)`.

### Task 2: Node_Network discovery carries bridge and VLAN vertices

**Files:**
- Modify: `scripts/Proxmox_VE_Node_Network.ad.groovy`
- Modify: `scripts/lib/pve_common.groovy` (new `pveNodeVlans`)
- Modify: `tests/harness.groovy` (carrier block ~2140; scale block ~2010), `tests/fixtures/pve_api.json` (pve1 guest lists), `tests/fixtures/scale.groovy`

**Interfaces:**
- Consumes: `pveGuestNics`, `pveBridgeKey`, `pveVlanKey`, `pveNodeBridgeNames` (Task 1); `pveCarrierCluster(what, true)`, `pveEri`, `pveDiscover`, `pveNodeGet`, `pveSelfNode`.
- Produces: `pveNodeVlans(String node, Set bridgeNames, Collection vnetNames) -> List<Map>` returning `[bridge: String, tag: int]`, unique, sorted by bridge then tag, from `/nodes/<node>/qemu` and `/nodes/<node>/lxc` (templates skipped) and each guest's `/config`, all through `pveNodeGet`, inside `pve.api.budget.ms`; a failed list or config prints one stderr line and is skipped. VLAN instance properties: `pve_node`, `pve_iface` (the bridge), `pve_iface_type: 'vlan'`, `pve_vlan` (the tag).

- [ ] **Step 1: Write the failing tests.** Add to `pve_api.json` (via Python round-trip, CRLF, 2-space indent, as the file already is) `/nodes/pve1/qemu` and `/nodes/pve1/lxc` lists matching `topoFixtures`' pve1 guests plus a template; make `topoFixtures` provide the pve1 configs from Task 1 and `/cluster/sdn/vnets` reachable through the node. Replace the `Node_Network` `expectCarried` with:
  ```groovy
  expectCarried('Proxmox_VE_Node_Network.ad.groovy', nodeHostProps, [
      'vmbr0'       : [key: topoKey('net', 'pve1', 'vmbr0'), type: 'VirtualSwitch'],
      'vmbr1'       : [key: topoKey('net', 'pve1', 'vmbr1'), type: 'VirtualSwitch'],
      'vmbr0-vlan10': [key: topoKey('net', 'pve1', 'vmbr0', 'vlan10'), type: 'VirtualSwitch'],
      'vmbr0-vlan20': [key: topoKey('net', 'pve1', 'vmbr0', 'vlan20'), type: 'VirtualSwitch'],
  ])
  ```
  and the id check to `['vmbr0', 'vmbr1', 'vmbr0-vlan10', 'vmbr0-vlan20'] as Set` (no `labnet`: Review Focus 1). Add `net-vlan-props`: the `vmbr0-vlan10` AD line carries `auto.pve_iface_type=vlan`, `auto.pve_iface=vmbr0`, `auto.pve_vlan=10`. Add `net-vlan-config-failure`: with `/nodes/pve1/qemu/101/config` set to null (501) in `withFixtures`, exit 0, `vmbr0` and `vmbr1` still discovered, one stderr line naming qemu/101. Add `Proxmox_VE_Node_Network.ad.groovy` to the `topo.namespace` step-aside list. In `scale.groovy` give every third guest `tag=` (10 + i % 4) on its `net0`, and add `/nodes/<n>/network` (one `vmbr0` bridge) and plain `/nodes/<n>/qemu` and `/nodes/<n>/lxc` lists. Add `scale-node-network`: `Proxmox_VE_Node_Network.ad.groovy` at scale with `nodeHostProps + ['pve.node': 'pve01']` exits 0, every request path starts with `/nodes/pve01/` or `/cluster/`, request count `<= 5 + scale['/cluster/resources?type=vm'].count { it.node == 'pve01' }`, and its VLAN ids equal the distinct `vmbr0-vlan<N>` among pve01's tagged guests.

- [ ] **Step 2: Run the harness; expect the new `topo-carriers`, `net-vlan-*` and `scale-node-network` FAILED lines.**

- [ ] **Step 3: Implement** `pveNodeVlans` and the discovery: bridges as now plus `pveEri(pveBridgeKey(...), 'VirtualSwitch')` when `pveCarrierCluster('Bridge', true)` gives a cluster; VNet names from `pveNodeGet('/cluster/sdn/vnets')` (an HTTP error means no SDN: empty; a connection error fails the run, as `pveSdn` does); VLAN lines with name `<bridge> VLAN <N>`, description `VLAN <N> on bridge <bridge> on <node>, used by guest NICs`, and `pveEri(pveVlanKey(...), 'VirtualSwitch')`. Rewrite the header comment to the spec's rule, including that an unused VLAN keeps its instance.

- [ ] **Step 4: Rebuild and run the harness; expect no FAILED lines.**

- [ ] **Step 5: Commit** `Node_Network: bridges and guest VLANs carry their topology vertices (A§3.4 amended 2026-10-30)`.

### Task 3: Node_Network collection reports VLAN instances

**Files:**
- Modify: `scripts/Proxmox_VE_Node_Network.collect.groovy`
- Modify: `scripts/lib/pve_common.groovy` (new `pveBatchInstances`)
- Test: `tests/harness.groovy` (beside the Node_Network collect checks, or after the SDN block)

**Interfaces:**
- Produces: `pveBatchInstances() -> List<Map>`: `[wildvalue: String, props: Map]` for each entry of the `datasourceinstanceProps` binding; each `props` lookup goes through a closure that tries `key` then `'auto.' + key`. Empty list when the binding is absent or null.

- [ ] **Step 1: Write the failing tests** `net-vlan-collect`: run the collect script with `nodeHostProps` and `datasourceinstanceProps` set to each of three shapes:
  - `['vmbr0 VLAN 10': [wildvalue: 'vmbr0-vlan10', 'auto.pve_iface': 'vmbr0', 'auto.pve_iface_type': 'vlan', 'auto.pve_vlan': '10']]`
  - the same with keys without `auto.`
  - absent (binding not set)

  Expect for the first two: `vmbr0-vlan10.Active` and `.Healthy` equal `vmbr0.Active` and `vmbr0.Healthy`, no `vmbr0-vlan10.Ports`; for the third: bridges only, exit 0, empty stderr. Also: a VLAN whose bridge is gone from `/network` emits nothing for it.

- [ ] **Step 2: Run the harness; expect `net-vlan-collect` FAILED.**

- [ ] **Step 3: Implement** `pveBatchInstances` and, in the collect script, after the bridge loop, emit `Active` and `Healthy` for each instance whose `pve_iface_type` is `vlan`, from its `pve_iface` bridge's row, under its `wildvalue`.

- [ ] **Step 4: Rebuild and run the harness; expect no FAILED lines.**

- [ ] **Step 5: Commit** `Node_Network: VLAN instances take their bridge's state (A§3.4 amended 2026-10-30)`.

### Task 4: Module text, product page and full local checks

**Files:**
- Modify: `modules/Proxmox_VE_Node_Network.json` (`description`, `searchKeywords` add `vlan`, `technicalNotes`, datapoint descriptions of `Active`/`Healthy` to say a VLAN reports its bridge's value, the `Healthy` alert body to name a bridge or VLAN)
- Modify: `modules/Proxmox_VE_Cluster_SDN.json` (`technicalNotes`: replace "drawn between the nodes (or the bridge the zone rides on) and the guests attached to the VNet" with "drawn between the nodes and the guests attached to the VNet")
- Modify: `modules/Proxmox_VE_Cluster_Topology.json` (`technicalNotes` Network sentence)
- Modify: `docs/proxmox-ve-monitoring.md` (module table line ~561 and the topology section's network sentence; mark VLAN reporting *(not yet verified)* until Task 5)
- Modify: `docs/exchange-listing.md` if it states the network layer

- [ ] **Step 1: Write the text.** `Node_Network`'s `technicalNotes` must say: one `/network` call per poll; discovery also reads this node's guest lists and configs hourly (needs `VM.Audit`, which `PVEAuditor` holds); instances are bridges and the VLANs guest NICs use; a VLAN reports its bridge's `Active` and `Healthy`, learned through `datasourceinstanceProps`; both carry `VirtualSwitch` vertices for `Proxmox_VE_Cluster_Topology`, none under `topo.namespace`/`topo.blacklist`; an unused VLAN keeps its instance until deleted by hand. Stay under 4,096 characters.

- [ ] **Step 2: Run every check in Global Constraints.** Expected: build clean (23 modules), compile passed, harness all passed, lint 0 errors and 4 warnings, both dashboards clean.

- [ ] **Step 3: Run the built discovery and topology plan against the lab from the workstation** (scratchpad runners `run_sdn.groovy`/`run_topo.groovy` pattern, with `pve.node` set per node): Node-A and Node-B each discover `vmbr0` with its key; no VLANs yet; the plan draws node → `vmbr0` → each guest.

- [ ] **Step 4: Commit** `Node_Network, SDN, Topology notes and product page: bridges and VLANs on the map (A§3.4 amended 2026-10-30)`.

### Task 5: Lab verification and spec §5

Uses the grants the user made for `claude-diag@pve!diag` (`PVESDNAdmin` on `/sdn`, `ClaudeNetTest` on `/vms/107` and `/vms/103`, `ClaudeMigrateTest` on `/vms/107`). Every lab change is listed so it can be undone.

- [ ] **Step 1: Lab step A.** Migration-Test (107, Node-B): add `net1 bridge=vmbr0,tag=30`, `net2 bridge=vmbr0,tag=40`, `net3 bridge=vmbr0` (untagged). Relay01 (103, Node-A): add `net1 bridge=vmbr0,tag=30`. Run the built discovery on each node and the plan from the workstation: Node-B gets `vmbr0-vlan30`, `vmbr0-vlan40`; Node-A `vmbr0-vlan30`; the plan draws both VLAN 30 vertices separately, one bridge → guest edge per guest.

- [ ] **Step 2: Lab step B and C.** Create zone `lmtest` (simple) with VNet `lmvnet1`, zone `lmvlan` (type `vlan`, bridge `vmbr0`, nodes Node-B only) with VNet `lmvnet2` tag 50; Apply. Add to 107 `net4 bridge=lmvnet1` and `net5 bridge=lmvnet2`. Check Node-B's `/network`: record whether `lmvnet1`/`lmvnet2` appear (Review Focus 1). Plan: node → `lmtest` from both nodes, node → `lmvlan` from Node-B only, VNet → guest, no bridge or VLAN vertex for the VNet NICs.

- [ ] **Step 3: User imports and polls.** Ask the user to import `dist/Proxmox_VE_Node_Network.json`, `Proxmox_VE_Cluster_SDN.json`, `Proxmox_VE_Cluster_Topology.json`, run Active Discovery for Node Network on both nodes, Poll Now on a VLAN instance (proves `datasourceinstanceProps`: values equal the bridge's), and send the Topology Test Script output and a map screenshot; then the Connections filter with Network unticked (step D).

- [ ] **Step 4: Migration.** Migrate 107 to Node-A in restart mode (`POST /nodes/<Node-B>/lxc/107/migrate`, `target=<Node-A>`, `restart=1`), wait for the task; plan draws its edges from Node-A's `vmbr0`/VLANs; Node-B's `vmbr0-vlan40` stays as an instance with no edges (the suite rule). Migrate back the same way.

- [ ] **Step 5: Clean up and confirm.** Remove `net1`-`net5` from 107 and `net1` from 103; delete `lmvnet2`, `lmvnet1`, `lmvlan`, `lmtest`; Apply; read both configs and `/cluster/sdn/zones` to confirm. Tell the user to run the revoke commands.

- [ ] **Step 6: Spec §5.** Add rows (local checks; portal import and live target) with what each step showed, mark the `datasourceinstanceProps` claim in §3.4 verified or record the fallback, update "Not yet seen on the lab". Lab scrub, commit `Spec §5: bridges and VLANs on the lab map (user 2026-10-30)`.
