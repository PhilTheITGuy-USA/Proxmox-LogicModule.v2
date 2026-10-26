/*
 * Script collection: this node's own health, asked of the node itself at the resource's
 * hostname -- never through another node, which would answer for itself.
 *
 * Three calls: /nodes/{node}/status (CPU, memory, uptime, load, swap, root filesystem),
 * /nodes/{node}/rrddata (Linux pressure stall figures, per-minute averages) and
 * /cluster/status (this node's own view of quorum, so a quorum loss alerts from every
 * node that survives it, whichever resource is down).
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def node = pveSelfNode()
    def status = pveNodeGet('/nodes/' + node + '/status') ?: [:]
    def swap = status.swap ?: [:]
    def rootfs = status.rootfs ?: [:]
    def memory = status.memory ?: [:]
    def cpuInfo = status.cpuinfo ?: [:]

    pveEmit(null, 'CPUUsagePercent', pveRound(((status.cpu ?: 0) as double) * 100.0d))
    pveEmit(null, 'CPUCount', cpuInfo.cpus ?: 0)
    pveEmit(null, 'MemoryUsedBytes', memory.used ?: 0)
    pveEmit(null, 'MemoryCapacityBytes', memory.total ?: 0)
    pveEmit(null, 'MemoryUsagePercent', pvePercent(memory.used, memory.total))
    pveEmit(null, 'UpTimeSeconds', status.uptime ?: 0)

    /*
     * loadavg entries are typed as strings in the Proxmox schema, not numbers. They are
     * coerced explicitly here; arithmetic on them without coercion silently concatenates.
     */
    def load = (status.loadavg ?: []).collect { it == null ? 0.0d : it.toString().toDouble() }
    pveEmit(null, 'LoadAverage1m', load.size() > 0 ? load[0] : 0)
    pveEmit(null, 'LoadAverage5m', load.size() > 1 ? load[1] : 0)
    pveEmit(null, 'LoadAverage15m', load.size() > 2 ? load[2] : 0)

    def cpuCount = cpuInfo.cpus ?: 0
    pveEmit(null, 'CPUSockets', cpuInfo.sockets ?: 0)
    pveEmit(null, 'CPUCores', cpuInfo.cores ?: 0)
    // Load per core is the figure that means the same thing across differently sized nodes.
    pveEmit(null, 'LoadPerCore', cpuCount ? pveRound((load ? load[0] : 0.0d) / (cpuCount as double)) : 0)

    pveEmit(null, 'SwapUsedBytes', swap.used ?: 0)
    pveEmit(null, 'SwapCapacityBytes', swap.total ?: 0)
    pveEmit(null, 'SwapUsagePercent', pvePercent(swap.used, swap.total))

    pveEmit(null, 'RootFSUsedBytes', rootfs.used ?: 0)
    pveEmit(null, 'RootFSCapacity', rootfs.total ?: 0)
    pveEmit(null, 'RootFSFreeSpace', rootfs.avail ?: 0)
    pveEmit(null, 'RootFSUsedPercent', pvePercent(rootfs.used, rootfs.total))

    // "wait" is the node's IO wait, reported on a 0..1 scale exactly like cpu. Sustained
    // IO wait is the clearest signal that storage, not CPU, is the constraint.
    pveEmit(null, 'IOWaitPercent', pveRound(((status.wait ?: 0) as double) * 100.0d))

    /*
     * KSM reclaims memory by merging identical pages across guests. A node with KSM
     * disabled reports no ksm block at all, and a zero there would read as "KSM is running
     * and saving nothing" rather than "KSM is off", so it is withheld.
     */
    def ksm = status.ksm ?: [:]
    if (ksm.shared != null) {
        pveEmit(null, 'KSMSharedBytes', ksm.shared)
    }

    /*
     * Pressure stall figures are percent of time stalled. Each is withheld when the node
     * does not report it, which is what a kernel without pressure accounting does: a zero
     * would read as "never stalled".
     */
    def rrd = pveLatestRrd(pveNodeGet('/nodes/' + node + '/rrddata?timeframe=hour&cf=AVERAGE') ?: [])
    def pressure = { String field -> rrd?.get(field) != null ? pveRound(rrd[field] as double) : null }
    if (pressure('pressurecpusome') != null) { pveEmit(null, 'PressureCPUSomePercent', pressure('pressurecpusome')) }
    if (pressure('pressureiosome') != null) { pveEmit(null, 'PressureIOSomePercent', pressure('pressureiosome')) }
    if (pressure('pressureiofull') != null) { pveEmit(null, 'PressureIOFullPercent', pressure('pressureiofull')) }
    if (pressure('pressurememorysome') != null) { pveEmit(null, 'PressureMemorySomePercent', pressure('pressurememorysome')) }
    if (pressure('pressurememoryfull') != null) { pveEmit(null, 'PressureMemoryFullPercent', pressure('pressurememoryfull')) }

    // This node's own view of the cluster. A standalone host has no cluster row and no
    // quorum to lose, so both are withheld there.
    def clusterStatus = pveNodeGet('/cluster/status') ?: []
    def clusterRow = clusterStatus.find { it.type?.toString() == 'cluster' }
    if (clusterRow) {
        pveEmit(null, 'Quorate', clusterRow.quorate?.toString() == '1' ? 1 : 0)
        pveEmit(null, 'NodesOnline',
                clusterStatus.count { it.type?.toString() == 'node' && it.online?.toString() == '1' })
    }

    return 0
} catch (Exception exception) {
    System.err.println('Proxmox node detail collection failed: ' + exception.message)
    return 2
}
