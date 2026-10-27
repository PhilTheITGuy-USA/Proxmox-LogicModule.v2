#!/usr/bin/env python3
"""
Proxmox VE dashboard covering the Tier 1 modules, laid out like with like.

Layout on LogicMonitor's 12-column grid:

    rows  1-6   Cluster Health | Capacity and Overcommit | Alert Status (noc)
                Cluster Health Over Time               |
    rows  7-9   CPU:     Node CPU | Node Load Per Core | Node CPU Pressure
    rows 10-12  Memory:  Node Memory | Node Swap | Node Memory Pressure
    rows 13-18  Storage: Storage Used | Node Root Filesystem | Node IO Pressure
                         Storage Capacity (table) | Guest Disk Throughput
    rows 19-21  Network: Guest Network Throughput (full width)
    rows 22-29  Top 10:  Guests by CPU | Guests by Memory | Guests by Disk
                         Nodes by CPU | Nodes by Memory | Storage by Used
    rows 30-32  Guest Status (full width)
    rows 33-36  Proxmox VE Alerts (full width)

Every "top N by X" widget is a table, not a graph: sorted descending on its first column,
ten rows a page, percentages drawn as bars. The time graphs are per node or per storage,
where a line per object reads well.

Structure mirrors LogicMonitor's own VMware and Hyper-V dashboards: capacity,
performance and status are separate tiles rather than one fat one, for the same reason
the modules are separate.
"""

from dashboards import Dashboard, module_ref

# Addressed as "<displayedAs> (<name>)". build.py checks each of these resolves to a
# real module with the datapoints used below, because nothing in the portal would.
CLUSTER = module_ref("Proxmox VE Cluster Status", "Proxmox_VE_Cluster_Status")
NODE_DETAIL = module_ref("Proxmox VE Node Detail", "Proxmox_VE_Node_Detail")
GUEST_PERF = module_ref("Proxmox VE Cluster Guest Performance", "Proxmox_VE_Cluster_GuestPerformance")
GUEST_STATUS = module_ref("Proxmox VE Cluster Guest Status", "Proxmox_VE_Cluster_GuestStatus")
STORAGE = module_ref("Proxmox VE Cluster Storage Capacity", "Proxmox_VE_Cluster_StorageCapacity")

# Node Detail is single-instance: one instance per node resource, named after the module.
NODE_ROWS = "##HOSTNAME##"


def build() -> Dashboard:
    d = Dashboard(
        name="Proxmox VE",
        description=(
            "Core metrics for Proxmox VE, grouped like with like: cluster health and "
            "capacity, then CPU, memory, storage and network, then top-10 tables of "
            "guests, nodes and storage, guest state and alerts."
        ),
        group="Proxmox VE",
        # "*" matches whichever resources hold the cluster role. Scope it to a resource
        # group in the dashboard's token settings if you monitor more than one cluster.
        resource_group="*",
    )

    # ------------------------------------------------------ rows 1-6: overview

    # HealthScore and NodesOnlinePercent are the cluster health figures. HealthScore is
    # withheld when no online node reports pressure or load, so its tile can be blank
    # then; NodesOnlinePercent is emitted on every run.
    d.numbers(1, 1, 4, 3, "Cluster Health", [
        (CLUSTER, "HealthScore", "Health Score", 0),
        (CLUSTER, "NodesOnlinePercent", "Nodes Online %", 0),
        (CLUSTER, "NodesOnline", "Nodes Online", 0),
        (CLUSTER, "NodesOffline", "Nodes Offline", 0),
    ], description="Health Score is nodes online weighted by how stalled each one is "
                   "(pressure stall figures), 0 without quorum; 100 is every node online "
                   "and unstalled. It warns below 70 and errors below 50.")

    d.numbers(1, 5, 4, 3, "Capacity and Overcommit", [
        (CLUSTER, "GuestsRunning", "Guests Running", 0),
        (CLUSTER, "GuestsTotal", "Guests Total", 0),
        (CLUSTER, "VCPUOvercommitRatio", "vCPU Overcommit", 2),
        (CLUSTER, "MemoryOvercommitRatio", "Memory Overcommit", 2),
    ], description="Overcommit is allocated over physical capacity across the cluster. "
                   "The ratios ship without thresholds; a defensible number is "
                   "site-specific.")

    d.alert_status(1, 9, 4, 6, "Proxmox VE Alert Status", "Proxmox VE*")

    d.graph(4, 1, 8, 3, "Cluster Health Over Time", [
        d.series(CLUSTER, "HealthScore", legend="Health score (##HOSTNAME##)"),
        d.series(CLUSTER, "NodesOnlinePercent", legend="Nodes online % (##HOSTNAME##)"),
    ], "%", description="Only the resource collecting for the cluster draws a line; "
                        "the standby's stays empty unless it stood in.")

    # ------------------------------------------------------------ rows 7-9: CPU

    d.graph(7, 1, 4, 3, "Node CPU Utilisation",
            [d.series(NODE_DETAIL, "CPUUsagePercent", display="area", legend="##HOSTNAME##")], "%")
    d.graph(7, 5, 4, 3, "Node Load Per Core",
            [d.series(NODE_DETAIL, "LoadPerCore", legend="##HOSTNAME##")], "#", maximum="NaN",
            description="Load average normalised by core count, which is the form that "
                        "compares across differently sized nodes.")
    d.graph(7, 9, 4, 3, "Node CPU Pressure",
            [d.series(NODE_DETAIL, "PressureCPUSomePercent", legend="##HOSTNAME##")], "%",
            description="Share of time some task waited for CPU (pressure stall "
                        "information): contention that utilisation alone does not show.")

    # -------------------------------------------------------- rows 10-12: memory

    d.graph(10, 1, 4, 3, "Node Memory Utilisation",
            [d.series(NODE_DETAIL, "MemoryUsagePercent", display="area", legend="##HOSTNAME##")], "%")
    d.graph(10, 5, 4, 3, "Node Swap Used",
            [d.series(NODE_DETAIL, "SwapUsagePercent", display="area", legend="##HOSTNAME##")], "%")
    d.graph(10, 9, 4, 3, "Node Memory Pressure",
            [d.series(NODE_DETAIL, "PressureMemorySomePercent", legend="##HOSTNAME##")], "%",
            description="Share of time some task waited for memory: reclaim and swap "
                        "stalls before memory runs out.")

    # ------------------------------------------------------- rows 13-18: storage

    d.graph(13, 1, 4, 3, "Storage Used",
            [d.series(STORAGE, "UsedPercent", display="area")], "%",
            timescale="2days")
    d.graph(13, 5, 4, 3, "Node Root Filesystem Used",
            [d.series(NODE_DETAIL, "RootFSUsedPercent", display="area", legend="##HOSTNAME##")], "%")
    d.graph(13, 9, 4, 3, "Node IO Pressure", [
        d.series(NODE_DETAIL, "PressureIOSomePercent", legend="##HOSTNAME## some"),
        d.series(NODE_DETAIL, "IOWaitPercent", legend="##HOSTNAME## IO wait"),
    ], "%", description="IO pressure is the share of time some task waited for IO; IO "
                        "wait is CPU time spent idle with IO outstanding.")

    d.table(16, 1, 6, 3, "Storage Capacity", STORAGE, [
        d.column("UsedPercent", "Used %", warn=80, error=90),
        d.column("UsedGB", "Used GB", display_type="raw", rounding=0),
        d.column("FreeGB", "Free GB", display_type="raw", rounding=0),
        d.column("CapacityGB", "Capacity GB", display_type="raw", rounding=0),
    ], top_x=-1, description="Every storage, fullest first.")

    d.graph(16, 7, 6, 3, "Guest Disk Throughput", [
        d.series(GUEST_PERF, "DiskReadRateMB", legend="##INSTANCE## read"),
        d.series(GUEST_PERF, "DiskWriteRateMB", legend="##INSTANCE## write"),
    ], "MB/sec", maximum="NaN",
       description="Byte counters only. Proxmox exposes no per-guest IOPS or storage "
                   "latency, so there is no equivalent of the Nutanix latency graphs.")

    # ------------------------------------------------------- rows 19-21: network

    d.graph(19, 1, 12, 3, "Guest Network Throughput", [
        d.series(GUEST_PERF, "DataRateRxMB", legend="##INSTANCE## rx"),
        d.series(GUEST_PERF, "DataRateTxMB", legend="##INSTANCE## tx"),
    ], "MB/sec", maximum="NaN",
       description="netin/netout are cumulative counters in Proxmox and are collected "
                   "as derive, so this is a rate rather than a climbing total.")

    # -------------------------------------------------------- rows 22-29: top 10

    # Each sorts on its first column; the second gives context.
    d.table(22, 1, 4, 4, "Top 10 Guests by CPU", GUEST_PERF, [
        d.column("CPUUsagePercent", "CPU %", warn=90, error=95),
        d.column("MemoryUsagePercent", "Memory %", warn=90, error=95),
    ])
    d.table(22, 5, 4, 4, "Top 10 Guests by Memory", GUEST_PERF, [
        d.column("MemoryUsagePercent", "Memory %", warn=90, error=95),
        d.column("CPUUsagePercent", "CPU %", warn=90, error=95),
    ])
    d.table(22, 9, 4, 4, "Top 10 Guests by Disk Used", GUEST_PERF, [
        d.column("DiskUsagePercent", "Disk %", warn=80, error=90),
        d.column("DiskUsedGB", "Used GB", display_type="raw", rounding=0),
    ], description="Containers only: Proxmox reports disk use for containers but not "
                   "for VMs, so VMs have no row here.")

    d.table(26, 1, 4, 4, "Top 10 Nodes by CPU", NODE_DETAIL, [
        d.column("CPUUsagePercent", "CPU %", warn=85, error=92),
        d.column("MemoryUsagePercent", "Memory %", warn=85, error=92),
    ], label=NODE_ROWS)
    d.table(26, 5, 4, 4, "Top 10 Nodes by Memory", NODE_DETAIL, [
        d.column("MemoryUsagePercent", "Memory %", warn=85, error=92),
        d.column("CPUUsagePercent", "CPU %", warn=85, error=92),
    ], label=NODE_ROWS)
    d.table(26, 9, 4, 4, "Top 10 Storage by Used", STORAGE, [
        d.column("UsedPercent", "Used %", warn=80, error=90),
        d.column("FreeGB", "Free GB", display_type="raw", rounding=0),
    ])

    # ------------------------------------------------- rows 30-32: guest status

    # Power state, lock and HA health are inventory facts rather than trends, so a
    # full-width table reads better than a graph.
    d.table(30, 1, 12, 3, "Guest Status", GUEST_STATUS, [
        d.column("Status", "Running", display_type="raw", rounding=0),
        d.column("HAManaged", "HA Managed", display_type="raw", rounding=0),
        d.column("HAError", "HA Error", display_type="raw", warn=1, rounding=0),
        d.column("Locked", "Locked", display_type="raw", rounding=0),
        d.column("UpTimeSeconds", "Uptime (s)", display_type="raw", rounding=0),
    ], top_x=-1,
       description="Status ships without a threshold: a stopped guest is usually "
                   "stopped on purpose. HAError only fires for guests the cluster "
                   "itself considers broken.")

    # ---------------------------------------------------------- rows 33-36: alerts

    d.alert_table(33, 1, 12, 4, "Proxmox VE Alerts", "Proxmox_VE*")

    return d
