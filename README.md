# Proxmox VE LogicModule suite — v2

The second generation of the Proxmox VE suite for LogicMonitor. It models a cluster, its nodes
and, optionally, its guests as separate resources, the way LogicMonitor monitors vSphere:

- **An Enhanced Script NetScan** finds the cluster and creates its resources, grouped under
  `Cluster - <name>/Nodes` (and `/Guests`), without duplicating devices already in the portal.
- **Cluster modules** (quorum, health score, capacity, storage, backups, snapshots, guest status
  and performance, topology) run on one active resource with a standby that takes over while the
  active is down, at a fixed number of API calls per poll whatever the cluster's size.
- **Node modules** (detail and pressure, disks, services, certificates, replication,
  subscription) run on each node's own resource and ask only that node.
- **Guests as resources** are opt-in and filtered: every resource is a host unit, while the
  guest instances on the cluster resource are free.

v2 is standalone: it needs no v1 installed and never touches v1's modules. It is built: 23
modules and the NetScan, checked locally, imported into a portal and run against a two-node
Proxmox VE 9.2 lab cluster. The architecture spec's §5
(`docs/superpowers/specs/`) records what each of those layers has verified.

## Install

See [`docs/proxmox-ve-monitoring.md`](docs/proxmox-ve-monitoring.md): the Proxmox API token,
the LogicMonitor API user for duplicate checking, and the NetScan's properties.

## Build and test

```sh
python build/build.py                                        # build dist/ and run its checks
docker compose -f tests/docker-compose.yml run --rm compile  # groovyc, Groovy 4 / JDK 17
docker compose -f tests/docker-compose.yml run --rm tests    # mock-API harness
```

Import with `pwsh tools/Import-Modules.ps1` after `Connect-LMAccount` (Logic.Monitor module); it
lists the PropertySources for import through the portal. The NetScan script is
`dist/netscan/Proxmox_VE_NetScan.groovy`.

## Repository

- `modules/`, `scripts/` (with the shared preamble in `scripts/lib/`), `dashboards/`, `build/`,
  `tests/`, `tools/` — the suite's sources, build, harness and import tool
- `docs/proxmox-ve-monitoring.md` — the product page, in the layout of LogicMonitor's own vSphere
  article
- `docs/superpowers/specs/` and `plans/` — the v2 design and its implementation plan
- `docs/research/` — what LogicMonitor's suites and the Proxmox community do, with sources
- `docs/v1/` — v1's own documentation; v2 began as a copy of v1 at `2e0237b` (v1's tag `v1.0.0`; `dca3f23` since v1's history rewrite, 2026-10-27), tagged `v1-base` here
