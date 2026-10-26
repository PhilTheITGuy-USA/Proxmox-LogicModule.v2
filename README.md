# Proxmox VE LogicModule suite — v2

The second generation of the Proxmox VE suite for LogicMonitor. It models a cluster, its nodes
and, optionally, its guests as separate resources, the way LogicMonitor monitors vSphere. v2 is
standalone: it needs no v1 installed.

The code is v1's, copied in verbatim from v1 commit `2e0237b`; v2 is designed but not yet built
on top of it.

- `docs/proxmox-ve-monitoring.md` — the product page (draft), in the layout of LogicMonitor's own
  vSphere article
- `docs/superpowers/specs/` — the v2 design, for review before any plan
- `docs/research/` — what LogicMonitor's suites and the Proxmox community do, with sources
- `docs/v1/` — v1's own documentation, as copied
- `modules/`, `scripts/`, `dashboards/`, `build/`, `tests/` — the suite's sources, build and
  harness (`python build/build.py` builds `dist/`)
