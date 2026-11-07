# HealthScore Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `HealthScore` the lowest of five area scores, and make storage fullness depend on a store's size, for release as v2.8.0.

**Architecture:** One pure preamble closure, `pveHealthScore`, decides every area and the score, so the harness tests it without a Collector. `Proxmox_VE_Cluster_Status` gathers its inputs from calls it already makes. A second pure closure, `pveEffectiveUsed`, serves both `Proxmox_VE_Cluster_StorageCapacity` (its alert) and the score (`HealthStorage`), so the two cannot disagree.

**Tech Stack:** Groovy 4 on JDK 17 (Collector scripts), Python build, Docker compile and harness jobs.

**Spec:** `docs/superpowers/specs/2026-10-21-proxmox-v2-architecture-design.md`, §3.2 "The cluster health score".

## Global Constraints

- Work on branch `health-score`. Never merge, push, tag or release without the user's go-ahead.
- Sources are mostly CRLF. Use the Edit tool for anything with a backslash: the Bash tool collapses doubled backslashes.
- Lab names stay out of tracked files: use `Node-A`, `Node-B`, `192.0.2.x`. Run `python ../lab-scrub/check.py` before every push.
- A preamble closure must be defined before the closures that use it, and is found by name (`shake_preamble`).
- `tests/harness.groovy` `run()` is at the JVM's method limit: every new test section goes inside its own closure, called once.
- The harness reads `dist/`: run `python build/build.py` before it.
- The user runs every command on nodes and guests. The API token is read-only. Protected guests stay out of every test.
- No new API calls in `Proxmox_VE_Cluster_Status`, unless Task 1 proves one is needed.
- Pinned values: steps 75, 50, 25; HA 50; levels 90 and 95; pressure floor 75; CPU gate 85%; reference size 100 GB; exponent 0.2; flat `plugintype` values `zfspool`, `rbd`, `cephfs`.

## Review Focus

1. A store reporting `available` with `maxdisk` 0 or missing: `EffectiveUsedPercent` is withheld, nothing divides by zero, and the store counts as 100.
2. No quorum while HA or storage could not be read: the score is 0, not withheld, because the lowest area is already known.
3. A Proxmox version with no `fencing` entry: `HealthHA` is not penalised for it.
4. Some online nodes answer `rrddata` and some don't, or the time budget runs out: `HealthPressure` uses those that answered, and is withheld only when none did.
5. A shared store seen from two nodes, available on one and not on the other: the worst row wins, so 25.

---

### Task 1: Lab check: how Proxmox shows a disabled and an unavailable store

This decides the storage skip rule, so it comes before any code.

**Files:** none. Record the result in the spec in Task 5.

**Interfaces:**
- Produces: the value of `DISABLED_RULE` used in Task 3: which `/cluster/resources` rows are skipped as disabled.

- [ ] **Step 1: Ask the user to create a throwaway store.** On Node-A (lab name in `CLAUDE.local.md`):

```sh
mkdir -p /mnt/hs-test && mount -t tmpfs -o size=1G tmpfs /mnt/hs-test
pvesm add dir hs-test --path /mnt/hs-test --content backup --is_mountpoint yes
```

- [ ] **Step 2: Read `/cluster/resources?type=storage` for three states.** Read it mounted; after `pvesm set hs-test --disable 1`; and after `pvesm set hs-test --disable 0` then `umount /mnt/hs-test`. Allow a minute for each state. Record `status` and every other field of the row, or that the row is absent.

- [ ] **Step 3: Decide `DISABLED_RULE`.**
  - Disabled row absent, or its `status` differs from an unmounted store's: skip by that row shape. No new call.
  - Disabled and unavailable look the same: stop and tell the user. Telling them apart needs one `/storage` call per poll, which the spec excludes, so it is the user's decision.

- [ ] **Step 4: Ask the user to clean up:** `pvesm remove hs-test`, `umount /mnt/hs-test` if mounted, `rmdir /mnt/hs-test`.

---

### Task 2: `EffectiveUsedPercent` on Storage Capacity

**Files:**
- Modify: `scripts/lib/pve_common.groovy` (after `pvePercent`, about line 578)
- Modify: `scripts/Proxmox_VE_Cluster_StorageCapacity.collect.groovy`
- Modify: `modules/Proxmox_VE_Cluster_StorageCapacity.json:23`
- Test: `tests/harness.groovy`

**Interfaces:**
- Produces: `pveEffectiveUsed = { Map store -> Double }`. Reads `disk`, `maxdisk`, `plugintype`. Returns the size-adjusted percent rounded by `pveRound`, or `null` when `maxdisk` is 0 or missing.

- [ ] **Step 1: Write the failing tests** in a new closure `effectiveUsedCases`, called once, next to the health score section. Build rows with `disk` and `maxdisk` in bytes (GB = 10^9).

```groovy
// note name, then the assertion
'effective-small'    : eff(100, 90, 'dir') == 90d      // 100 GB: unchanged
'effective-tiny'     : eff(32, 91, 'dir') == 91d       // under 100 GB: unchanged, never tightened
'effective-500g'     : Math.abs(eff(500, 92.75, 'dir') - 90d) < 0.1d
'effective-12t'      : Math.abs(eff(12000, 96.16, 'dir') - 90d) < 0.1d
'effective-48t'      : Math.abs(eff(48000, 97.09, 'dir') - 90d) < 0.1d
'effective-128t'     : Math.abs(eff(128000, 98.8, 'dir') - 95d) < 0.1d
'effective-floor'    : eff(12000, 10, 'dir') == 0d     // clamps at 0
'effective-full'     : eff(12000, 100, 'dir') == 100d
'effective-flat'     : ['zfspool', 'rbd', 'cephfs'].every { eff(48000, 91, it) == 91d }
'effective-nosize'   : effRow([disk: 0, maxdisk: 0, plugintype: 'dir']) == null && effRow([plugintype: 'dir']) == null
'effective-collect'  : the batch script prints EffectiveUsedPercent for storage/pve1/local (10.0) and storage/pve1/ceph-vm (50.0, flat), and none for storage/pve2/backup.nfs
'effective-threshold': the built module has EffectiveUsedPercent threshold '> 80 90 95', and UsedPercent has none
```

- [ ] **Step 2: Run them to see them fail.**

Run: `python build/build.py && docker compose -f tests/docker-compose.yml run --rm tests > "$TMP/harness.txt" 2>&1; grep FAILED "$TMP/harness.txt"`
Expected: FAILED lines naming `effective-*`.

- [ ] **Step 3: Implement `pveEffectiveUsed`.**

```
scale = 1 for a flat plugintype, or a store of 100 GB or less; else (100 / sizeGB) ^ 0.2
result = max(0, 100 - (100 - usedPercent) / scale)
```

Use the unrounded percent, so a value does not step on rounding, and round once at the end. Cap `usedPercent` at 100.

- [ ] **Step 4: Emit it** in the collect script after `UsedPercent`, only when not null.

- [ ] **Step 5: Change the module.** Remove `threshold` and `alertBody` from `UsedPercent`. Add after it, with no graph:

```json
{"name": "EffectiveUsedPercent", "description": "How full the store is for its size, 0-100: the plain percentage, eased for stores over 100 GB so the same levels suit every size. ZFS and Ceph stores read their real percentage. Withheld when the store reports no size.", "threshold": "> 80 90 95", "conditional": true, "alertBody": "Proxmox storage ##INSTANCE## is nearly full for its size, reading ##VALUE## of 100. UsedPercent and FreeGB show the real figures.\n\nThis started at ##START##, ##DURATION## ago."}
```

Write the `\n` with the Edit tool.

- [ ] **Step 6: Run the checks.**

Run: `python build/build.py`, then `docker compose -f tests/docker-compose.yml run --rm compile`, the harness as in Step 2, and the lint from `CLAUDE.md`.
Expected: no FAILED lines, lint 0 errors.

- [ ] **Step 7: Commit.**

```bash
git add scripts modules tests
git commit -m "Storage: EffectiveUsedPercent scales with size and takes the alert from UsedPercent (user 2026-11-06)"
```

---

### Task 3: Area scores in `HealthScore`

**Files:**
- Modify: `scripts/lib/pve_common.groovy` (`pveNodeStall` line 853, `pveHealthScore` line 922)
- Modify: `scripts/Proxmox_VE_Cluster_Status.collect.groovy`
- Modify: `modules/Proxmox_VE_Cluster_Status.json:66,159`
- Test: `tests/harness.groovy:845-860`, `:915-921`

**Interfaces:**
- Consumes: `pveEffectiveUsed` (Task 2); `DISABLED_RULE` (Task 1).
- Produces:
  - `pveNodeStall = { Map rrdRow, Map status -> Double }`, same signature, new rule.
  - `pveHealthScore = { List statusRows, Map stallByNode, Map ha, List stores -> Map }`. `ha` is `null` when unread, else `[services: int, masterAlive: boolean, armed: Boolean, errored: int]`, with `armed` `null` when Proxmox has no fencing entry. `stores` is `null` when unread, else `/cluster/resources` rows of type `storage`. Returns `NodesOnline`, `NodesTotal`, `NodesOnlinePercent`, and whichever of `HealthNodes`, `HealthHA`, `HealthStorage`, `HealthPressure`, `HealthScore` are known.

- [ ] **Step 1: Rewrite the tests.** Move the block at lines 845-860 into a closure `healthCases`, called once, and replace the `stall` assertion at 915-921.

```groovy
// pveNodeStall(rrd, status)
[pressurecpusome: 89.7, pressureiofull: 0, pressurememoryfull: 0, cpu: 0.30]  -> 0d      // capped guest, idle cores
[pressurecpusome: 89.7, pressureiofull: 0, pressurememoryfull: 0, cpu: 0.85]  -> 89.7d   // the gate is inclusive
[pressurecpusome: 1, pressureiosome: 12, pressureiofull: 10, cpu: 0.1]        -> 10d     // full wins over some
[pressurecpusome: 1, pressureiosome: 30, pressurememorysome: 1, cpu: 0.1]     -> 30d     // no full: some
[pressurecpusome: 2.5, cpu: 0.1]                                              -> 0d      // pressure known, no load fallback
[cpu: 0.1] with loadavg 6.0, 4 cpus                                           -> 100d
[cpu: 0.1] with loadavg 1.0, 4 cpus                                           -> 25d
[:] with [:]                                                                  -> null

// pveHealthScore: ok = [services: 1, masterAlive: true, armed: true, errored: 0]
'health-all-well'    : two nodes, stalls 0, ok, a store at 10% -> every area 100, HealthScore 100
'health-worst-wins'  : HA errored 1 and stalls 10/30 -> HealthHA 50, HealthPressure 80, HealthScore 50
'health-no-ha'       : [services: 0, masterAlive: false, armed: false, errored: 0] -> HealthHA 100
'health-ha-dead'     : masterAlive false -> 50
'health-ha-unarmed'  : armed false -> 50
'health-ha-nofencing': armed null, all else well -> 100                                  // Review Focus 3
'health-nodes'       : 15 of 16 online -> HealthNodes 93.75, counted once
'health-quorum'      : not quorate, ha null, stores null -> HealthScore 0                // Review Focus 2
'health-store-90'    : effective 90 -> 75; 'health-store-95': effective 95 -> 50
'health-store-local' : local store unavailable on an online node -> 50
'health-store-shared': shared store available on one node, unavailable on the other -> 25 // Review Focus 5
'health-store-offline': unavailable store on an offline node -> 100
'health-store-nosize': available with maxdisk 0 -> 100                                   // Review Focus 1
'health-store-disabled': a row matching DISABLED_RULE -> 100
'health-pressure-floor': stalls 90/90 -> HealthPressure 75
'health-pressure-part' : one of two online nodes has no stall -> mean of the other       // Review Focus 4
'health-withheld-ha' : ha null -> no HealthHA, no HealthScore, other areas present
'health-withheld-store': stores null -> no HealthStorage, no HealthScore
'health-withheld-press': no stalls -> no HealthPressure, no HealthScore
'health-standalone'  : no cluster row -> HealthNodes 100, counts as quorate
'health-collect'     : Cluster_Status on the fixture: HealthNodes 50, HealthHA 50, HealthStorage 100, HealthPressure 99.66, HealthScore 50
'health-no-alert'    : built module: HealthScore and the four areas have no threshold
```

- [ ] **Step 2: Run them to see them fail** (command as Task 2 Step 2). Expected: FAILED lines naming `health-*` and `stall`.

- [ ] **Step 3: Change `pveNodeStall`.** I/O and memory each use full when present, else some. CPU some counts only when `rrdRow.cpu` is 0.85 or more. Take the largest. Use the load fallback only when the row has no pressure key at all. Update its comment.

- [ ] **Step 4: Rewrite `pveHealthScore`** to the spec's table. Move it after `pveEffectiveUsed` if needed. A storage row is skipped when its node is offline or it matches `DISABLED_RULE`. Pressure is `max(75, 100 - mean)` over online nodes with a stall. `HealthScore` is 0 when not quorate; otherwise the minimum, and absent if any area is absent.

- [ ] **Step 5: Reorder `Proxmox_VE_Cluster_Status.collect.groovy`.** Build `ha` inside the existing HA `try`, reusing `services`, `errored`, `masterAlive` and `fencing`, and leave it `null` on failure. Use `resources.findAll { it.type == 'storage' }` for `stores`, with `null` if the resources read failed. Keep the `rrddata` loop where it is. Move the `pveHealthScore` call and its emits to after the HA block. A failure of `/cluster/resources` stops the script today, so wrap nothing new unless the emit order needs it. `NodesOnlinePercent` must still print if a later read fails, so emit it from the row counts where it is now.

- [ ] **Step 6: Change the module.** On `HealthScore`: remove `threshold` and `alertBody`, and set the description to `Cluster health, 0-100: the lowest of quorum, HealthNodes, HealthHA, HealthStorage and HealthPressure. 0 without quorum. Withheld when an area cannot be read.` Add `HealthNodes`, `HealthHA`, `HealthStorage` and `HealthPressure`, each `conditional: true`, no threshold, with a one-line description. Add the four to the Cluster Health graph in far-apart colours; `max` stays 100.

- [ ] **Step 7: Run all checks** (build, compile, harness, lint, and the dashboard check from `CLAUDE.md`). Expected: no FAILED lines, lint 0 errors, dashboards OK, `atScale` request counts unchanged.

- [ ] **Step 8: Commit.**

```bash
git add scripts modules tests
git commit -m "HealthScore: the lowest of five areas, no alert of its own (user 2026-11-06)"
```

---

### Task 4: Lab tests

Import the two changed modules, then run each case, reading scheduled polls only. The user runs every node command. Give full commands, and say where to run them, one test at a time.

**Files:** none.

- [ ] **Step 1: Before importing, note any open alert** on `HealthScore` or `UsedPercent`. To test that case, first drive one open: lower `UsedPercent`'s threshold on one store instance below its real use, in the portal.

- [ ] **Step 2: Import.** `Import-LMLogicModuleFromFile -FilePath 'dist\X.json' -Type datasources -Format json -HandleConflict FORCE_OVERWRITE` for `Proxmox_VE_Cluster_Status` and `Proxmox_VE_Cluster_StorageCapacity`. Confirm the portal's scripts match `dist/scripts/` and that the open alert cleared or stayed. Record which.

- [ ] **Step 3: Normal running.** Expected: all areas near 100, `HealthScore` near 100, no new alerts. The large backup disk reads `EffectiveUsedPercent` well below `UsedPercent`.

- [ ] **Step 4: Capped container.** `stress-ng --cpu 1` in a one-core container on Node-B for four polls. Expected: `HealthPressure` stays near 100 while `PressureCPUSomePercent` is high.

- [ ] **Step 5: Every core loaded.** `stress-ng --cpu 0` on Node-B itself for four polls. Expected: CPU at 85% or more, and `HealthPressure` falls. If it never reaches 85%, say so; do not report the gate as proven.

- [ ] **Step 6: HA.** `ha-manager add ct:<test guest> --state started`, confirm `HealthHA` 100, stop `pve-ha-crm` on the non-master node then the master. Expected: `HealthHA` 50, `HealthScore` 50, one alert (`HAMasterPresent`). Restart, confirm 100, `ha-manager remove ct:<test guest>`. Warn: no node restart until HA reads `standby`, about 15 minutes.

- [ ] **Step 7: Storage.** A throwaway `tmpfs` store as Task 1. Fill it past 90%, then past 95%, with `fallocate`; then unmount it. Expected: `HealthStorage` 75, 50, then 50 for a local store. Remove the store.

- [ ] **Step 8: Withheld.** Set a test token without `Sys.Audit` on `/` on one resource's `pve.token.credential`, as in the 2026-11-02 test. Expected: `HealthHA` and `HealthScore` read No Data, and the troubleshooter reports the permission. If it doesn't, tell the user: the spec relies on it. Restore the token.

---

### Task 5: Docs and verification record

**Files:**
- Modify: `docs/proxmox-ve-monitoring.md` (new section, and the module table rows for Cluster Status and Storage Capacity)
- Modify: `docs/superpowers/specs/2026-10-21-proxmox-v2-architecture-design.md`

- [ ] **Step 1: Add "### Upgrading from v2.7.0"** above "### Upgrading from v2.6.2". It must say: what `HealthScore` now means and that its older history is not comparable; the `HealthScore` alert is gone; the storage alert moved to `EffectiveUsedPercent`, with the size table; a threshold set by hand on `UsedPercent` must move; a first warning now comes later on large stores; ZFS and Ceph read real percentages; what happens to an alert already open (Task 4 Step 2); the token consequence. Re-import `Proxmox_VE_Cluster_Status` and `Proxmox_VE_Cluster_StorageCapacity`.

- [ ] **Step 2: Add spec §5 rows** for each Task 4 result, the Task 1 finding, and a local-checks row. Change the section's "not yet built" to the date it was built. List what was not driven (ZFS and Ceph scaling, a store large enough to reach the levels).

- [ ] **Step 3: Rebuild, run the scrub check, commit.**

Run: `python build/build.py && python ../lab-scrub/check.py`
Expected: `clean`.

```bash
git add docs
git commit -m "Product page and spec: v2.7.0 upgrade notes and the health score lab results (lab <date>)"
```

---

### Task 6: Lessons and release

- [ ] **Step 1: Skill.** If Task 1 or Task 4 taught something about the platform, add it on its own skill branch: the rule, a lint check where possible, and a mutation case in `tests/test_tools.py`, which must print ALL PASSED. Use a worktree if the skill checkout is on someone else's branch.

- [ ] **Step 2: Ask the user** before merging, pushing, tagging and publishing. Then follow the release recipe: merge `health-score` to `main`, tag `v2.8.0`, `git push origin main v2.8.0`, `gh release create` with the zip and the NetScan, delete the branch, and update memory (`v2-next.md`, `MEMORY.md`).
