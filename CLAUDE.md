# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

The second generation of the Proxmox VE LogicModule suite, **built**: 19 modules and an Enhanced
Script NetScan, on `main` (plan `docs/superpowers/plans/2026-10-25-proxmox-v2.md`, executed
2026-10-26). It began as a verbatim copy of v1 at `2e0237b` (v1's tag `v1.0.0`; `dca3f23` since v1's history rewrite, 2026-10-27), in the commit
tagged `v1-base`, so `git diff v1-base` shows every v2 change; v1 itself lives on in the sibling repo `..\Proxmox-LogicModule`.

**v2 is standalone** (user, 2026-10-25): it never requires v1, never changes v1, and **there is no
migration from v1** -- no one runs either version, so nothing is planned or built for one. Module
names (`Proxmox_VE_Cluster_*`, `Proxmox_VE_Node_*`, `addERI_Proxmox_VE_*`) and the token property
**`pve.token.credential`** (never v1's `pve.api.token.credential`) differ from v1's only so the two
never collide in a portal.

## Layout

- `modules/*.json` -- module definitions. `scripts/*.groovy` -- their scripts; the build prepends
  the shared preamble `scripts/lib/pve_common.groovy` to each.
- `scripts/Proxmox_VE_NetScan.groovy` -- the NetScan body: the Collector-only reads (Proxmox, the
  `lm.api` portal lookup) and the emit. Its decisions live in `scripts/lib/pve_netscan.groovy`'s
  pure `pveNetscanPlan(input)`, so the harness tests them without a Collector.
- `dashboards/*.json` -- the final dashboards, `Proxmox VE` and `Proxmox VE Infrastructure`: the
  portal's own exports (user, 2026-10-27), kept verbatim. The build checks them against `modules/`
  and copies them into `dist/dashboards/` byte for byte; nothing generates them. A layout change
  is made in the portal and re-exported, then checked for lab names before it replaces the file.
- `build/build.py` -- assembles `dist/` and runs its cross-checks; `dashboards.py` (the dashboard
  validator), `groovylint.py`.
- `tests/` -- the Docker compile job, `harness.groovy` (mock Proxmox API, stand-in snippets for
  the NetScan smoke test) and `fixtures/` (including `scale.groovy`: 16 nodes, 1,000 guests).
- `tools/Import-Modules.ps1` -- imports `dist\*.json` through the Logic.Monitor PowerShell module
  (pwsh 7, a token whose role can manage modules). It cannot import PropertySources: it lists
  them for the portal's own import.
- `dist/` is generated and gitignored: never edit or commit it. `dist/netscan/` holds the NetScan
  script and a `.console.groovy` copy with a property block for the Collector debug console.

## Commands

From the repo root; the workspace `..\CLAUDE.md` has the PowerShell forms of the lint.

```sh
python build/build.py                                        # build dist/ and run its checks
docker compose -f tests/docker-compose.yml run --rm compile  # groovyc, Groovy 4 / JDK 17
docker compose -f tests/docker-compose.yml run --rm tests    # mock-API harness
python ../logicmonitor-modules-skill/skill/scripts/lm_lint_module.py dist
python ../logicmonitor-modules-skill/skill/scripts/lm_dashboards.py check dist/dashboards/*.json --modules modules
```

The harness has no single-test filter: redirect its output to a file and read the `FAILED`
lines (`note('<name>', ...)` names each check). Rebuild before running it -- it reads `dist/`.

## Build rules that bite

- **`statusNames` on a datapoint** (`{"0": "Offline", "1": "Online"}`) becomes LogicMonitor's
  `statusDisplayNames`, shape from LM's monitoring-recipes template, portal-confirmed 2026-10-27. `check_status_names` reads
  the values off the script's `pveEmit(..., 'Name', cond ? 1 : 0)` and refuses names for any
  other set, and an alert body that doesn't use `##ALERTVALUEDISPLAYSTATUSNAME##`.
- **Each module ships only the preamble it reaches, without comments** (`shake_preamble`,
  `strip_comments`): an embedded script is capped at **49,135 characters** (portal API, 2026-10-26),
  checked by `check_script_lengths`. A preamble closure must be defined before the closures that
  use it (a Groovy closure can't see a later script variable), and the shaker finds references
  by name, so don't call a closure through a dynamic name.
- **The NetScan is 30,249 bytes against LogicMonitor's recommended 32 KB.** Its output also has
  its indentation halved (`compact_indent`, which refuses text holding `'''`, `"""` or `/$`,
  the marks of a multi-line string). The harness's `netscan-size` fails above 31 KB, keeping a
  kilobyte free; past that, trim the NetScan rather than raise the limit.
- Collector objects are not Java collections: `hostProps` and `netscanProps` are
  `com.santaba.common.util.ParamMap` (`get` / `set`, no `put`, not a `Map`). Never type a
  parameter that receives them as `Map`.
- Most sources are CRLF. When a Bash heredoc or `sed` must write a backslash, use the Edit tool instead:
  the Bash tool collapses doubled backslashes.

## The v2 architecture in brief

The specs (`docs/superpowers/specs/`, approved 2026-10-25; changes need the user's approval) are
the authority; this is the map.

- **Three resource roles by category**, set by whoever creates the resource, never inferred:
  `ProxmoxVE_Cluster` (≈ vCenter), `ProxmoxVE_Node` (≈ ESXi host), `ProxmoxVE_Guest` (≈ VMwareVM,
  opt-in via `pve.netscan.guests`, off by default: every resource is a host unit, instances are
  free). A standalone host carries both Cluster and Node categories.
- **The cluster role is an active and a standby resource** (A§3.1), the property
  `pve.cluster.role`, never a category. With a cluster address of its own (VIP, proxy, DNS name):
  a cluster resource plus the lowest-ID node as standby; otherwise the two lowest-ID nodes. A
  resource that doesn't answer on its own address is dead in LogicMonitor and silences its own
  alerts, so the standby collects only while the one ahead of it is down. `pveRole()` decides on
  each poll: an active node collects only with its own corosync running and quorum (or a
  two-node cluster); nodes read their own API first; a standby that stops collecting runs once
  more to clear its alerts (the hand-back, kept in `lm.cache`).
- **Cluster modules stay O(1) API calls per poll** and fail over across `pve.api.hosts` (on 5xx
  and connection errors, never 4xx; a `/nodes/<name>` path tries two addresses at most). **Node
  modules ask only their own node** and never fail over. O(guests) loops go to the owning node,
  with a per-node breaker, `pve.api.concurrency` and the `pve.api.budget.ms` budget.
- **The NetScan** creates and groups resources (`Cluster - <name>/Nodes|Guests`), sends every
  property with an explicit value on every run (a re-run overwrites and adds, never removes),
  deduplicates through `lm.api` (`pve.serial` first, then display name, hostname, address), and
  never deletes: it reports orphans and address changes in `pve.netscan.report` on the active
  resource, since the portal shows a NetScan's stderr nowhere. With `hostname.source=lm` it
  adopts a device it matches, and the portal's hostname and display name win on every run;
  adoption also needs the NetScan's *All matching resources* (product page, step 10).
- **Lab facts about NetScans** (S3, 2026-10-26): a manual save of a resource detaches it from its
  NetScan (`scanConfigId` 0 in the audit log) and the NetScan still updates it; the audit log
  records only some of a NetScan's updates, so read the resource; `system.resourceUpdatedOn`
  does not change on a NetScan update.
- **Topology:** guest vertices are keyed by the bare MAC of the first NIC, which GuestStatus
  instances and `addERI_Proxmox_VE_GuestResource` also carry; node keys are synthesised from name
  and corosync address and stamped by `addERI_Proxmox_VE_Node`.

## Verification -- the one record

What each layer has proved. Spike detail is in the architecture spec's §5 table; update both here
when a layer changes.

- **Local checks** (2026-10-27, after the review fixes, the lab-detail scrub and the status
  names): build clean; compile job passed; harness 1,489 of 1,489; lint 0 errors, 4 warnings;
  both dashboard exports check clean.
- **Portal import** (2026-10-26): every module imports, through `tools/Import-Modules.ps1` and the
  portal for the PropertySources; the NetScan runs on the Collector, the trimmed 29,784-byte build included; the dashboards in `dashboards/` are the portal's exports of the final layout (user, 2026-10-27). Every module re-imported 2026-10-27 with the review's notes (user). The status-names build re-imported too (user, 2026-10-27): the names show on the datapoints, and an alert on a stopped guest's `Status` (a temporary `< 1` threshold) read "Status: Not running (0)".
- **Live target**, the lab cluster `Labcluster` (two nodes, PVE 9.2.3, no HA, no Ceph, no
  replication), 2026-10-26: S2, S3, S4, S5, S6 and S8 done -- the NetScan creates Node-A (active),
  Node-B (standby) and the guest Webapp01, and a re-run adds nothing and leaves the expected
  properties; the standby takes over when corosync or pveproxy stops on the active and hands back
  with its alerts cleared. The guest ERI's bare-MAC key joins a NetScan-created guest to its
  map vertex: Webapp01, drawn alone beside its vertex before the fix, is drawn once, under
  Node-A (user, 2026-10-26). **Not yet seen on the lab:** Replication and Ceph (nothing to read),
  a cluster address (VIP/DNS), a large portal's one-at-a-time lookups, adoption under the
  default duplicate-IP exclusion. S3: with every guest selected, the six guests monitored over SNMP/WMI are caught by
  address and not sent; with `hostname.source=lm` and *All matching resources*, Nvr01 is adopted
  -- Proxmox properties, `ProxmoxVE_Guest` beside its six SNMP categories, the Guests group beside
  its own, same device -- and re-runs rewrite values on the NetScan's own and on detached
  resources under the defaults. A run with every guest selected then adopted the other five
  (Media01, audio01, Relay01, Kiosk01, automation01: one `adopted:` line each in the report,
  portal names kept, Nvr01's too, so the `lm` display-name fix holds). Unexplained: three runs that missed Node-A's value while it was
  attached. S7's sharing half is deferred (user, 2026-10-26).

## Documents

- `docs/exchange-listing.md` -- the suite-level text for the LM Exchange submission (short
  description, overview, module table, keywords) and the checks to make before submitting.
- `docs/proxmox-ve-monitoring.md` -- the user-facing product page, in the outline and table
  formats of LogicMonitor's [VMware vSphere Monitoring](https://www.logicmonitor.com/support/vmware-vsphere-monitoring)
  article (user, 2026-10-25), without its "Migration from Legacy Modules" section. Keep that
  outline, write the wording ourselves, keep it in step with the specs, and mark design intent no
  lab run has confirmed *(not yet verified)*.
- `docs/superpowers/specs/` -- the architecture spec (A) and the NetScan spec (N). N tags claims
  **[v1]**, **[vmware]**, **[unverified]** with the spike that settles each, or **[S<n>]** once a
  spike has seen it on the lab.
- `docs/research/` -- what LogicMonitor's own vSphere (and Nutanix) suites do. Claims are tagged
  **[doc]**, **[script]** or **[inferred]**; keep that tagging. The vSphere NetScan the user
  supplied is LogicMonitor's code: summarise it, never copy it in.
- `docs/v1/` -- v1's own documentation, as copied; "v1 DESIGN §7" and the like point there.

Spec edits are dated and attributed to their evidence (a portal check, a live target, the user's
stated position, a LogicMonitor doc URL, a review); commit messages follow the same style, e.g.
`Spec §3.6: ... (v1 portal check 2026-10-22)`.
