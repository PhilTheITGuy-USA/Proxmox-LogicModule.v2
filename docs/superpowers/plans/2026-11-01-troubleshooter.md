# Proxmox VE Troubleshooter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `Proxmox_VE_Troubleshooter`, an always-on DataSource whose instances are the problems that stop collection, each named with its fix, cleared by value and auto-deleted after 30 days.

**Architecture:** The checks are one preamble closure, `pveTroubleshoot(probes)`, over a map of injectable probes (resolve, connect, ping, TLS, GET), so the harness drives every branch with fakes and one run against the mock API. The module's discovery prints one instance per finding; its BatchScript collection reruns the checks and prints `Problem` 1/0 per discovered instance. The skill's lint and the build gain one narrow exception: `autoDeleteInstances` (never `deleteInactiveInstances`) for `*_Troubleshooter` modules.

**Tech Stack:** Groovy 4 / JDK 17 (`groovy:4-jdk17` Docker), Python 3 build, the `logicmonitor-modules` skill (its own repo, `../logicmonitor-modules-skill`).

**Spec:** `docs/superpowers/specs/2026-10-21-proxmox-v2-architecture-design.md` §3.2, "The troubleshooter" (cited **A§3.2T**), approved by the user 2026-11-01; background `docs/research/2026-11-01-lm-troubleshooters.md`.

## Global Constraints

- Module name `Proxmox_VE_Troubleshooter`, displayed as `Proxmox VE Troubleshooter`, group `Proxmox VE`.
- AppliesTo exactly `hasCategory("ProxmoxVE_Cluster") || hasCategory("ProxmoxVE_Node")` (the build's `info` selector). No role check, no `pve.netscan.reachable` exclusion.
- Discovery `15m`; collection `batchscript`, `5m`; `multiInstance` and `useWildValueAsUniqueIdentifier` true.
- Active Discovery: `autoDeleteInstances` **true**, `deleteInactiveInstances` **false**. Every other module keeps both false.
- Wildvalues exactly: `settings`, `address`, `ping`, `certificate`, `token`, `permissions`, `node`, and `pveWildValue('failover-' + <address as written in pve.api.hosts>)`.
- Chain order as above; a check that fails stops the chain; `failover-*` runs only after `permissions` passes.
- Required privileges on `/`, propagated: `Sys.Audit`, `VM.Audit`, `Datastore.Audit`, `SDN.Audit`. Not `VM.GuestAgent.Audit`.
- Datapoint `Problem`, statusNames `{"0": "Resolved", "1": "Problem"}`, threshold `> 0 0`, alert body using `##DSIDESCRIPTION##` and `##ALERTVALUEDISPLAYSTATUSNAME##`.
- Instance property `auto.pve_finding` = the finding (printed through `pveDiscover` as key `pve_finding`).
- Embedded scripts ≤ 49,135 characters (`check_script_lengths`); preamble closures defined before their users, called by name only (the shaker).
- Collector objects are not `Map`s: never type a parameter that receives `hostProps` as `Map`.
- Sources are CRLF: where a backslash must be written, use the Edit tool, not a Bash heredoc or `sed`.
- Lab stand-ins only in tracked files (`Node-A`, `192.0.2.x`, ...); `python ../lab-scrub/check.py` before any push, in each repo touched.
- `dist/` is never edited or committed. Work on branch `troubleshooter` (this repo) and `troubleshooter-auto-delete` (skill repo).

## Review Focus

1. **An address with a port or IPv6 in `system.hostname`** (`host:8443`, `[fd00::1]`) -- `address` must connect to the host and port `pveHostUrl` builds, and name both. Test: Task 2 `trouble-address-ipv6`.
2. **`pve.api.hosts` holding the resource's own address, duplicates, or URL-form entries** -- each address checked once and the own address not at all. Test: Task 2 `trouble-failover-dedupe`.
3. **`/access/permissions` without a `/` entry** (the token's ACL only on `/vms` or `/nodes`) -- all four privileges reported missing, not a crash. Test: Task 2 `trouble-permissions-no-root`.
4. **A standalone host** (both categories, `/cluster/status` with no `cluster` row) -- `node` passes without asking corosync. Test: Task 2 `trouble-node-standalone`.
5. **A probe that throws something unexpected** (a `RuntimeException` from a malformed reply) -- discovery exits 2 and keeps its instances; collection exits 2. Test: Task 3 `trouble-ad-throws`.

---

### Task 1: The skill allows 30-day auto-delete for troubleshooters only

Repo `../logicmonitor-modules-skill`, branch `troubleshooter-auto-delete` off `main`. The skill is
live through the `~\.claude\skills\logicmonitor-modules` junction: while this branch is checked
out, every session sees its rules. Check `main` out again if the work pauses before the merge.

**Files:**
- Modify: `skill/scripts/lm_lint_module.py:263-268` (`check_active_discovery`)
- Modify: `skill/references/module-export-schema.md:224-228`, `skill/references/collector-scripting.md:112-118`, `skill/SKILL.md:38` and `:72`, `skill/references/suite-design.md:191`, `skill/references/other-module-types.md:59`
- Test: `tests/test_tools.py` (`module_mutations`, `module_must_pass`)

**Interfaces:**
- Produces: lint behaviour Task 3's built module must pass: `autoDeleteInstances: true` accepted only when `module["name"]` ends `_Troubleshooter` and `deleteInactiveInstances` is false.

- [ ] **Step 1: Write the failing cases** in `tests/test_tools.py`, from the `Proxmox_VE_Cluster_Nodes` fixture (`nodes`):
  - `module_mutations`: `"AD autoDeleteInstances on"` -- `nodes` with `activeDiscovery.autoDeleteInstances = True` (must fail);
    `"troubleshooter deletes immediately"` -- `name = "Proxmox_VE_Troubleshooter"`, `autoDeleteInstances = True`, `deleteInactiveInstances = True` (must fail).
  - `module_must_pass`: `"troubleshooter auto-deletes after 30 days"` -- `name = "Proxmox_VE_Troubleshooter"`, `autoDeleteInstances = True`, `deleteInactiveInstances = False`.
- [ ] **Step 2: Run** `python tests/test_tools.py` -- expected: `1 FAILED` (the must-pass case; the two mutations already fail).
- [ ] **Step 3: Change `check_active_discovery`.** `deleteInactiveInstances` true is an error everywhere, message: "deletes instances and their history as soon as discovery leaves them out". `autoDeleteInstances` true is an error unless the name ends `_Troubleshooter`, message: "an instance discovery leaves out -- a successful but incomplete answer -- disappears and can no longer alert; only a *_Troubleshooter, whose instances are findings, may auto-delete (after 30 days)".
- [ ] **Step 4: Rewrite the rule in the references** with the reason from research §2: a non-zero discovery exit keeps instances; the dangers are a successful, incomplete answer and a deleted instance that cannot alert; LogicMonitor's two settings (Delete Immediately, Delete After 30 Days with re-association); the troubleshooter exception and why. Keep each file's existing provenance tags; the exception is **[LM doc]** for the settings, **[inferred]** for the exception's safety until Task 5's lab run.
- [ ] **Step 5: Run** `python tests/test_tools.py` -- expected last line `ALL PASSED`.
- [ ] **Step 6: Scrub and commit** -- `python ../lab-scrub/check.py` (expected `clean`), then
  `git commit -am "Auto-delete: allowed after 30 days for *_Troubleshooter modules only; the rule's reason corrected (Proxmox v2 troubleshooter, user 2026-11-01)"`.

### Task 2: `pveTroubleshoot`, the checks

**Files:**
- Modify: `scripts/lib/pve_common.groovy` -- a new section `// ----- troubleshooter` after `pveRole` (it uses `pveHostUrl`, `pveGetAt`, `pveWildValue`, `pveConfigError`)
- Modify: `tests/fixtures/pve_api.json` -- add `/access/permissions?path=/`: `{"/": {"Sys.Audit": 1, "VM.Audit": 1, "Datastore.Audit": 1, "SDN.Audit": 1, "Pool.Audit": 1}}`
- Test: `tests/harness.groovy` -- a `// ----- troubleshooter` block before `server.stop(0)`

**Interfaces:**
- Produces:
  - `pveTroubleProbes`: a map of closures, the real ones, overridden key by key by a `pveTestProbes` binding when present (as `pveState` does with `pveTestState`):
    `resolve(String host)` -- throws `UnknownHostException`;
    `connect(String host, int port)` -- throws `ConnectException` / `SocketTimeoutException`;
    `ping(String host)` -> `boolean`;
    `tls(String base)` -- an HTTPS handshake to `base`, throws `javax.net.ssl.SSLException`;
    `get(String base, String path)` -> the `data` payload, as `pveGetAt`.
  - `pveTroubleshoot(Map probes)` -> `[findings: List<Map>, reached: Set<String>]`; each finding `[id: String, name: String, finding: String]`, in chain order. `reached` holds the id of every check run to a verdict, passed or failed, including `failover-*` ids. A check that does not apply (`certificate` over `http` or with `pve.api.insecure=true`; `node` off a node; `failover-*` off a role resource) counts as reached and passed when it is in the chain's path, so an open alert from before clears.
- Own address: `pveHostUrl(pveHostProp('system.hostname'))`; host and port read from that URL with `java.net.URI` (brackets stripped for IPv6).

- [ ] **Step 1: Write the failing harness cases.** A helper `trouble = { Map props, Map probes = [:] -> runSource(preamble + '\nreturn pveTroubleshoot(pveTroubleProbes)', [hostProps: props, pveTestProbes: [ping: { h -> true }] + probes]).exit }`, with `nodeProps = nodeHostProps + ['system.categories': 'ProxmoxVE_Node']`. Assertions (`f` = findings, `ids = f*.id`):
  - `trouble-healthy`: `nodeProps` -> `f == []`, `reached == ['settings','address','ping','certificate','token','permissions','node'] as Set`.
  - `trouble-settings-missing`: no `pve.token.credential` -> `ids == ['settings']`, `reached == ['settings'] as Set`, finding contains `pve.token.credential`.
  - `trouble-settings-form`: token `hunter2` -> `ids == ['settings']`, finding contains `user@realm!tokenid=secret`; the harness `TOKEN` passes.
  - `trouble-address-dns` / `-refused` / `-timeout`: `resolve` throws `UnknownHostException`, `connect` throws `ConnectException`, `SocketTimeoutException` -> `ids == ['address']`, finding contains `does not resolve` / `refused` / `timed out` and the port; `'ping' !in reached`.
  - `trouble-address-ipv6`: `system.hostname` `[fd00::1]:8006`, `connect` recording its arguments -> called with `('fd00::1', 8006)`.
  - `trouble-ping`: `ping` false -> `ids == ['ping']`, finding contains `ICMP`.
  - `trouble-cert`: `pve.api.url` `https://127.0.0.1:1`, `tls` throws `javax.net.ssl.SSLHandshakeException('PKIX path building failed')` -> `ids == ['certificate']`, finding contains `pve.api.insecure` and `PKIX`; with `pve.api.insecure=true` -> `'certificate' in reached`, `ids` excludes it.
  - `trouble-token`: wrong token of the right form -> `ids == ['token']`, finding contains `expired`.
  - `trouble-permissions-missing`: fixture `{"/": {"Sys.Audit": 1, "VM.Audit": 1}}` -> finding contains `Datastore.Audit` and `SDN.Audit`, not `Sys.Audit`; `{"/": {..., "Datastore.Audit": 0}}` -> finding contains `propagat`.
  - `trouble-permissions-no-root`: fixture `{"/vms": {"VM.Audit": 1}}` -> all four named.
  - `trouble-permissions-empty`: `/cluster/resources` `[]` -> finding contains `privilege separation`.
  - `trouble-node-mismatch`: `pve.node` `pve9` -> `ids == ['node']`, finding contains `pve1`; services fixture with corosync `active-state: inactive` -> finding contains `corosync`; `pve.netscan.reachable=false` -> finding contains `NetScan`.
  - `trouble-node-standalone`: `/cluster/status` without a `cluster` row and services read failing (fixture removed) -> `f == []`.
  - `trouble-node-off`: `hostProps` (no node category) -> `'node' !in reached`, `f == []`.
  - `trouble-failover`: `hostProps + ['pve.api.hosts': '127.0.0.1:1,127.0.0.1:' + server2.address.port]` -> `ids == ['failover-127-0-0-1-1']`, finding names `127.0.0.1:1`; with `pve.cluster.role` `none` -> no `failover-*` in `reached`.
  - `trouble-failover-dedupe`: `pve.api.hosts` lists the own address, the server2 address twice and once as a URL -> server2 checked once (its request log grows by one `/version`), own address not checked.
  - `trouble-failover-gated`: wrong token plus `pve.api.hosts` -> `ids == ['token']`.
- [ ] **Step 2: Build and run the harness** -- `python build/build.py`, then `docker compose -f tests/docker-compose.yml run --rm tests > "$TMP/harness.txt" 2>&1; grep FAILED "$TMP/harness.txt"`. Expected: the `trouble-*` cases fail (`pveTroubleshoot` undefined).
- [ ] **Step 3: Implement `pveTroubleProbes` and `pveTroubleshoot(Map probes)`** to the table in A§3.2T. Names are `<Check>: <fix in a few words>` (e.g. `Token: replace the secret in pve.token.credential`); findings are full sentences naming the property, address, port, privilege or node concerned. `token` takes any non-401 failure of `/version` as its finding too ("the API at <base> answered: <message>"), so the chain still stops on one instance. `node` reads `/cluster/status` and `/nodes/<local>/services` through `probes.get` at the own address; a services read that fails skips the corosync test, as `pveCorosyncRunning` does.
- [ ] **Step 4: Rerun the harness** -- expected `PASSED  all N checks`, N larger than before by the cases added.
- [ ] **Step 5: Commit** -- `git commit -am "Preamble: pveTroubleshoot, the troubleshooter's chain of checks over injectable probes (spec §3.2, user 2026-11-01)"`.

### Task 3: The module, its scripts and the build

**Files:**
- Create: `modules/Proxmox_VE_Troubleshooter.json`, `scripts/Proxmox_VE_Troubleshooter.ad.groovy`, `scripts/Proxmox_VE_Troubleshooter.collect.groovy`
- Modify: `build/build.py` -- `APPLIES_TO_BY_NAME` (`"Proxmox_VE_Troubleshooter": "info"`); `build_module` (`autoDeleteInstances` from the definition, default false; `deleteInactiveInstances` always false, comment rewritten); a check in `check_definition` refusing `autoDeleteInstances` on any name not ending `_Troubleshooter`
- Modify: `tests/harness.groovy` -- exempt the module from the per-module conformance loop (a healthy resource discovers nothing, so its "discovered no instances" and "produced no output" checks do not apply) and add its own cases

**Interfaces:**
- Consumes: `pveTroubleshoot`, `pveTroubleProbes` (Task 2); `pveDiscover`, `pveEmit`, `pveBatchInstances`; Task 1's lint.
- Produces: `dist/Proxmox_VE_Troubleshooter.json`.

- [ ] **Step 1: Write the failing harness cases:**
  - `trouble-ad`: AD run with a wrong token -> exit 0, one line matching `AD_LINE`, id `token`, description = the finding, properties contain `auto.pve_finding=`.
  - `trouble-ad-healthy`: AD run with `nodeProps` -> exit 0, no output.
  - `trouble-ad-throws`: `pveTestProbes.get` throws `RuntimeException` -> AD exit 2, no output; collection exit 2.
  - `trouble-collect`: wrong token, `datasourceinstanceProps` with `token`, `ping` and `failover-x` -> output exactly `token.Problem=1` and `ping.Problem=0`, nothing for `failover-x` (not reached).
  - `trouble-built`: `dist/Proxmox_VE_Troubleshooter.json` has `activeDiscovery.autoDeleteInstances == true`, `deleteInactiveInstances == false`, `discoveryInterval == '15m'`, `collectionInterval == '5m'`; every other `dist/*.json` with discovery has both false.
- [ ] **Step 2: Run** `python build/build.py` -- expected: it fails on the missing module (the cases cannot run yet).
- [ ] **Step 3: Write the definition and scripts.** The definition follows `Proxmox_VE_Node_Services.json`'s shape with the Global Constraints' values, `"autoDeleteInstances": true`, one datapoint `Problem` (description: "1 while this problem persists, 0 once it is resolved"), no graph, and `technicalNotes` stating: what it checks and in what order, why it has no instances while healthy, why it alone auto-deletes (after 30 days), the calls it makes, and the privileges. Discovery: `pveConfigError` is *not* an exit 2 here (it is the `settings` finding); otherwise as `Node_Services.ad` -- each finding through `pveDiscover(f.id, f.name, f.finding, [pve_finding: f.finding])`, exit 0; exit 2 on an exception. Collection: run the checks once; for each `pveBatchInstances()` entry whose wildvalue is in `reached`, `pveEmit(wildvalue, 'Problem', open.contains(wildvalue) ? 1 : 0)` (one occurrence, as `check_status_names` requires); exit 2 on an exception.
- [ ] **Step 4: Change the build** as listed under Files. Check the refusal: add `"autoDeleteInstances": true` to `modules/Proxmox_VE_Node_Services.json`, run `python build/build.py`, expect a failure naming the module and `_Troubleshooter`, then revert that file.
- [ ] **Step 5: Run everything:**
  - `python build/build.py` -- succeeds; module count 24.
  - `docker compose -f tests/docker-compose.yml run --rm compile` -- every `PASS`.
  - the harness -- `PASSED  all N checks`.
  - `python ../logicmonitor-modules-skill/skill/scripts/lm_lint_module.py dist` -- no errors (needs Task 1's skill branch checked out in `../logicmonitor-modules-skill`).
  - `python ../logicmonitor-modules-skill/skill/scripts/lm_dashboards.py check dist/dashboards/*.json --modules modules` -- clean.
- [ ] **Step 6: Commit** -- `git add modules scripts build tests && git commit -m "Proxmox_VE_Troubleshooter: problems as instances named with their fix, cleared by value, auto-deleted after 30 days (spec §3.2, user 2026-11-01)"`.

### Task 4: Documents

**Files:**
- Modify: `README.md:17` and its module bullets; `CLAUDE.md:7` -- 23 -> 24 modules (the build's `check_doc_counts` checks both)
- Modify: `docs/proxmox-ve-monitoring.md` -- a "Troubleshooter" subsection opening `## Troubleshooting` (what an instance means, that none means healthy, the eight checks and fixes, the 30-day removal); a row in the DataSource table (~line 596); a new `### Upgrading from v2.2.0` above `### Upgrading from v2.1.1` (what is added; import the module; nothing to change on resources)
- Modify: `docs/exchange-listing.md` -- a row `| \`Proxmox_VE_Troubleshooter\` | DataSource | 5 min |` and any counts in its text
- Modify: the spec §5 *Verification by layer* table -- a row: the troubleshooter, local checks (harness count, compile, lint), portal import and live target not yet

- [ ] **Step 1: Make the edits**; mark the unverified points of A§3.2T *(not yet verified)* on the product page.
- [ ] **Step 2: Run** `python build/build.py` -- expected success (`check_doc_counts` passes).
- [ ] **Step 3: Scrub and commit** -- `python ../lab-scrub/check.py` (expected `clean`), then `git commit -am "Docs: the troubleshooter on the product page, README, Exchange listing; spec §5 local checks (2026-11-01)"`.

### Task 5: Portal import and lab run (with the user)

Nothing here is code; each result is a dated row in spec §5 and, where it settles an **[unverified]** or **[inferred]** point, an amendment to A§3.2T and the skill (Task 1's tags).

- [ ] **Step 1:** The user imports `dist/Proxmox_VE_Troubleshooter.json` (`tools/Import-Modules.ps1`). Expected: accepted, and a portal export shows `autoDeleteInstances` true and `deleteInactiveInstances` false.
- [ ] **Step 2:** On Node-A, healthy -> no instances. Break the token's secret -> within 15 minutes a `token` instance and an alert whose body shows the finding (settles `##DSIDESCRIPTION##`).
- [ ] **Step 3:** Restore the secret -> the alert clears within 5 minutes (by value), and the instance leaves the tree at the next discovery.
- [ ] **Step 4:** Read `/access/permissions?path=/` through the troubleshooter's finding with a narrowed ACL (settles its shape).
- [ ] **Step 5:** Record steps 1-4 in spec §5; then merge `troubleshooter` and `troubleshooter-auto-delete` only when the user says so.
