# LM Exchange readiness review (2026-10-26)

Checked against the `logicmonitor-modules` skill's Exchange checklist (`suite-design.md` §10):
all 19 built modules in `dist/` and both dashboards, by script where the check is mechanical and
by reading where it is not. **Nothing was changed.** Status: saved for the next session (user,
2026-10-26); the proposal at the end awaits the user's go-ahead.

## Meets the checklist

- `Proxmox_VE_*` / `addERI_Proxmox_VE_*` names, `displayedAs`, descriptions and `searchKeywords`
  on every module; one suite group, `Proxmox VE`, with the two ERI PropertySources in `ERI`.
- `technicalNotes` on every module, 679-3,353 characters (limit 4,096), covering the calls each
  makes, cost, conditional datapoints and limits.
- Datapoints PascalCase and described; every alerting datapoint has `alertBody`; every datapoint
  feeds a graph, an alert or a dashboard except Node Disks `SizeBytes` (gap 8).
- `useWildValueAsUniqueIdentifier` on, no auto-delete, non-zero exit on failure.
- `//!/lib-groovy/v4` header on every script; TLS bypass (`pve.api.insecure`) off by default;
  scripts under 49,135 characters.
- The token is `pve.token.credential`; `Proxmox_VE_Info`'s properties are referred to by their
  stored `auto.*` names everywhere (the lint's 4 warnings are that rule, and it holds).
- Expensive modules on long intervals (Snapshots and Backups 60m, Disks and Certificates 240m).
- The install guide (`docs/proxmox-ve-monitoring.md`): least-privilege token, the API user,
  verification, troubleshooting by symptom; the proxy limitation is recorded.
- Imported and collecting in a real portal (`CLAUDE.md`, verification).
- No lab names, IPs, portal names or tokens in `dist/`, `modules/`, `scripts/`, `dashboards/`,
  `tools/`, the README or the product page.

## Gaps

**Before submission**

1. **The NetScan and the dashboards don't ship through Exchange,** which carries LogicModules
   only. The listing and the product page need to say where to get the NetScan script and the
   dashboard files. Without the NetScan, modules apply only to resources categorised by hand.
   *Decided 2026-10-27 (user): a public GitHub repo,* so the scrub below comes first. *Done
   2026-10-27, except going public (user: not yet): the product page's "Getting the NetScan
   Script and Dashboards" names the repo and the files; the lab details are scrubbed (below).*
2. **No `addCategory_*` self-application.** *Stated 2026-10-27 in every category-applied module's notes and on the product page; the listing still needs it.* The modules apply by the categories and roles the
   NetScan sets (the approved design); the product page covers manual adds. State it plainly in
   the listing and in one line of each module's notes, since a reviewer will ask.
3. ~~**No privilege note in 8 modules' technical notes:**~~ *Added 2026-10-27.* Cluster GuestPerformance, GuestStatus,
   Nodes, StorageCapacity, Topology, Proxmox_VE_Info, Node Detail, addERI_Proxmox_VE_Node. One
   line each (e.g. "needs PVEAuditor: Sys.Audit, VM.Audit, Datastore.Audit").
4. **Units are mixed and stated nowhere** (*2026-10-27, user: state the convention, don't
   normalise*): bytes (Node Detail, Node Disks), MB (Guest
   Performance), GB (Storage Capacity, Backups), as inherited from v1. Cheap fix: state the
   convention on the product page and in the affected notes. *Stated 2026-10-27: the product
   page's Units section and a units line in the 8 modules with unit datapoints.* Normalising would rename datapoints
   and touch the dashboards (possible now: no one runs the suite yet).
5. ~~**Dashboards not export-checked.**~~ Done 2026-10-27: the final dashboards are the portal's
   exports (`dashboards/Proxmox_VE.json`, `Proxmox_VE_Infrastructure.json`), checked clean and
   free of lab names.
6. **Name collisions.** Search Exchange for existing `Proxmox_VE_*` modules before submitting.
   *2026-10-27:* no v2 name collides with v1's (`Proxmox_VE_<Thing>`, `addCategory_Proxmox_VE`,
   `addERI_Proxmox_VE`, `addERI_Proxmox_VE_Guest`), and a web search found no other Proxmox
   LogicModules published. *Closed 2026-10-27:* the portal's Exchange catalog
   (`/setting/logicmodules/metadata`, 3,121 entries) has no Proxmox module of anyone else's; its
   only matches for the suite's names are this portal's own copies (`source: LOCAL`).

**Optional polish**

7. *Done and portal-confirmed 2026-10-27 (an alert read "Status: Not running (0)"):* 14 alerting status datapoints in
   12 modules carry names, and their alert bodies a `Status: ##ALERTVALUEDISPLAYSTATUSNAME##`
   line. Was: `statusDisplayNames` for the 1/0 status datapoints (Nodes `Status`, StorageCapacity `Status`,
   and similar), so alerts read "Offline" rather than "0". Today the mapping is in each
   description, which the checklist accepts; the build has no support for it yet, and it would
   need one import test.
8. ~~Node Disks `SizeBytes` feeds nothing~~ Dropped 2026-10-27 (user): no complex datapoint,
   dashboard RPN or virtual datapoint used it; `SizeGB` is computed in the script.
9. *Done 2026-10-27.* Product page, Import LogicModules: add "the ERI PropertySources before the TopologySource"
   (it says only "modules, then dashboards").
10. Snapshots ships no threshold: deliberate, explained in its notes with suggested starting
    values. Leave unless a reviewer asks.

**Outside Exchange, before making the repo public:** scrubbed 2026-10-27 -- names, LAN
addresses, MACs and an EUI-64 address replaced by stand-ins in every tracked file (the map is in
the gitignored `CLAUDE.local.md`). **Git history still holds them**: publish a fresh history (or
rewrite this one) rather than flipping this repo public.

## Proposed next step

Done 2026-10-27: 2 and 4 (stated), 3, 5, 8, 9. Still open: 1 (scrub the lab details, then make
the repo public and point the listing at it), 6 (search Exchange for name collisions), and the
optional 7 and 10.
