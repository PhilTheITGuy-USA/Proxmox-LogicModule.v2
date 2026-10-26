# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Design work for the second generation of the Proxmox VE LogicModule suite. **Nothing is built
yet**: there is no `build/`, `dist/`, `modules/` or test harness, so there are no build, lint or test
commands to run here. The workspace `..\CLAUDE.md` (skill use, lint commands, lessons going back
into the skill) applies once modules exist.

v1 is the sibling repo `..\Proxmox-LogicModule`, complete at `2e0237b` (19 modules,
portal-verified on a two-node lab).
**v2 is standalone** (user, 2026-10-25): it never requires v1 to be installed and never changes
v1. The implementation plan's first task copies all of v1 at `2e0237b` (19 modules, their scripts,
build, Docker harness, mock API, dashboard validation) into this repo verbatim; v2 customises its
own copy from there (spec §3.8). Until then, read v1's `CLAUDE.md` and `docs/DESIGN.md` before
proposing any build or code layout. References like "v1 DESIGN §7" in the docs here point there.

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
  (`Display Name | Type | Description | Replaces`), Migration from Legacy Modules. Keep that
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
- **An Enhanced Script NetScan** creates and groups resources (`Cluster - <name>/Nodes|Guests`,
  never grouped by node), deduplicates against existing devices (display name, hostname, MAC),
  and never deletes.
- **Licensing drives defaults:** every resource is one host unit, instances are free -- hence
  guests-as-resources is opt-in.
- **The cluster role is an active and a standby resource** (spec §3.1, after spike S2): a cluster
  resource plus a standby node where the cluster has its own address (VIP, proxy, DNS name), else
  the two lowest-ID nodes. A resource without an answering address is dead in LogicMonitor, and a
  dead resource suppresses its alerts; script data never keeps it alive. The standby collects
  only while the resource ahead of it is down.
