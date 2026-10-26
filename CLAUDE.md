# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

The second generation of the Proxmox VE LogicModule suite. **The code is still v1's:** all of v1
at commit `2e0237b` (19 modules, portal-verified on a two-node lab) was copied in verbatim on
2026-10-25, so every v2 change shows as a diff against it (spec §3.8). None of the v2 design is
built yet. v1 itself lives on in the sibling repo `..\Proxmox-LogicModule`.

**v2 is standalone** (user, 2026-10-25): it never requires v1 to be installed and never changes
v1; it customises its own copy.

Layout, as v1's (read `docs/v1/CLAUDE.md` and `docs/v1/DESIGN.md` before changing the build or
code layout; references like "v1 DESIGN §7" point there):

- `modules/*.json` -- module definitions; `scripts/*.groovy` -- their scripts, each prepended with
  the shared preamble `scripts/lib/pve_common.groovy` at build; `dashboards/*.py` -- dashboard
  sources; `build/` -- `build.py` (assembles `dist/`, with its cross-checks), `dashboards.py`,
  `groovylint.py`; `tests/` -- the Docker compile job, `harness.groovy` and its mock API fixture.
- `dist/` is generated and gitignored: never edit or commit it.
- `docs/v1/` -- v1's own CLAUDE.md, README, DESIGN, INSTALL and specs, as copied.

Commands (from the repo root; the workspace `..\CLAUDE.md` has the PowerShell forms of the lint):

```sh
python build/build.py                                        # build dist/ and run its checks
docker compose -f tests/docker-compose.yml run --rm compile  # groovyc, Groovy 4 / JDK 17
docker compose -f tests/docker-compose.yml run --rm tests    # mock-API harness
python ../logicmonitor-modules-skill/skill/scripts/lm_lint_module.py dist
python ../logicmonitor-modules-skill/skill/scripts/lm_dashboards.py check dist/dashboards/*.json --modules modules
```

At the copy, the build output was byte-identical to v1's and to the skill's
`tests/fixtures/proxmox/`; the compile job passed, the harness passed all 1,378 checks, the lint
gave 0 errors and 5 warnings (v1's own), and both dashboards checked clean (2026-10-25).

v2's token property is **`pve.token.credential`**, never v1's `pve.api.token.credential`: v1's
`addCategory_Proxmox_VE` applies all of v1 to any resource holding that one (spec §3.5).

## Documents

- `docs/research/` -- what LogicMonitor's own vSphere (and Nutanix) suites do. Claims are tagged
  **[doc]**, **[script]** or **[inferred]**; keep that tagging when adding to it. The vSphere
  NetScan the user supplied is LogicMonitor's code: summarise it, never copy it in (its contract
  is summarised in research §2a).
- `docs/superpowers/specs/` -- the v2 architecture spec and the NetScan spec it points to (§3.3),
  both **draft for review**. The workflow is spec → spikes (§5) → plan → code; don't start a plan
  or code until the user approves the specs and the open questions that block them (architecture
  §4, NetScan §11) are answered. The NetScan spec tags claims **[v1]**, **[vmware]**,
  **[unverified]** with the spike that settles each, or **[S<n>]** when a spike has seen it in the
  lab cluster `Labcluster` (PVE 9.2.3).

- `docs/proxmox-ve-monitoring.md` -- the user-facing product page, in the outline and table
  formats of LogicMonitor's [VMware vSphere Monitoring](https://www.logicmonitor.com/support/vmware-vsphere-monitoring)
  article (user, 2026-10-25): Technology Type, Compatibility, Setup Requirements, Add Resources
  (numbered NetScan steps with `Property | Value | Example | Required` tables), Manually Assigning
  Properties, Considerations, Troubleshooting, NetScan Troubleshooting, LogicModules in Package
  (`Display Name | Type | Description | Replaces`). LogicMonitor's "Migration from Legacy
  Modules" section is left out: no one runs either version, so there is no migration (user,
  2026-10-25). Keep that
  outline, write the wording ourselves, and keep it in step with the specs; *(not yet verified)*
  marks design intent no lab run has confirmed.

Spec edits are dated and attributed to their evidence (a portal check, a live target, the user's
stated position, a LogicMonitor doc URL); commit messages follow the same style, e.g.
`Spec §3.6: ... (v1 portal check 2026-10-22)`.

## The v2 architecture in brief

The point of v2 is to model Proxmox the way LogicMonitor models vSphere, instead of v1's "every
node, guest and storage object is an instance on the token-holding node":

- **Three resource roles by category**, set by whoever creates the resource (NetScan or a manual
  add), never inferred from whether a token works: `ProxmoxVE_Cluster` (≈ vCenter),
  `ProxmoxVE_Node` (≈ ESXi host), `ProxmoxVE_Guest` (≈ VMwareVM, opt-in via `pve.netscan.guests`,
  off by default). A standalone host carries both Cluster and Node categories.
- **Cluster-wide modules stay O(1) API calls per poll** on the cluster role, which fails over
  across `pve.api.hosts`; node modules ask only their own node and never fail over. Modules are
  named `Proxmox_VE_Cluster_*` / `Proxmox_VE_Node_*` so they never overwrite v1's on import;
  each starts from a v1 module (spec §3.2 maps them). Guest hypervisor metrics stay
  instances on the cluster role, wildvalues unchanged so a migration keeps history.
- **An Enhanced Script NetScan** creates and groups resources (`Cluster - <name>/Nodes|Guests`),
  deduplicates against existing devices (display name, hostname, address, MAC), and never
  deletes.
- **Licensing drives defaults:** every resource is one host unit, instances are free -- hence
  guests-as-resources is opt-in.
- **The cluster role is an active and a standby resource** (spec §3.1, after spike S2): a cluster
  resource plus a standby node where the cluster has its own address (VIP, proxy, DNS name), else
  the two lowest-ID nodes. A resource without an answering address is dead in LogicMonitor, and a
  dead resource suppresses its alerts; script data never keeps it alive. The standby collects
  only while the resource ahead of it is down. The role is the property `pve.cluster.role`, never a
  category.
- **A NetScan re-run overwrites and adds, never removes** (spike S5): so every property is sent
  every run with an explicit value (`none`, `ok`), categories mark only what a resource is, and the
  NetScan writes only groups whose membership never changes; pools and tags are dynamic groups.
