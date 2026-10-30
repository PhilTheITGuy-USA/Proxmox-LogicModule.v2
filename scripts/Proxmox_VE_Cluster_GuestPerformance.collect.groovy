/*
 * BatchScript collection: guest performance, cluster-wide. CPU, memory, network and disk
 * throughput come from one /cluster/resources call; pressure, ballooning and disk timings
 * from each online node's own guest lists (see guestDetail below).
 *
 * netin/netout/diskread/diskwrite are cumulative counters since guest start. They are
 * scaled to MB and declared as "derive" datapoints so LogicMonitor computes the rate and
 * discards the negative delta produced when a guest restarts and its counters reset.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}
// Only the resource that holds the cluster role now collects; the other prints nothing.
if (!pveRole('Proxmox_VE_Cluster_GuestPerformance').collect) {
    return 0
}

try {
    def guests = (pveGet('/cluster/resources?type=vm') ?: [])
        .findAll { it.type in ['qemu', 'lxc'] && !it.template }

    // Proxmox sends pressure as numbers for VMs and as strings ("2.97") for containers.
    def number = { value -> value != null && value.toString().isNumber() ? value.toString() as double : null }

    /*
     * Pressure, ballooning and disk timings are not in /cluster/resources. Each node's own
     * lists carry them: /nodes/<n>/qemu?full=1 (pressure, ballooninfo, blockstat) and
     * /nodes/<n>/lxc (pressure). One call per type, only to nodes /cluster/status reports
     * online and only where the node runs such a guest, inside pve.api.budget.ms. A list that
     * cannot be read costs its guests these figures, not the run.
     */
    def guestDetail = [:]
    def detailNodes = [] as Set
    def running = guests.findAll { it.status?.toString() == 'running' && it.node }
    if (running) {
        try {
            def online = (pveGet('/cluster/status') ?: [])
                .findAll { it.type?.toString() == 'node' && it.name && (it.online == null || it.online?.toString() == '1') }
                .collect { it.name.toString() } as Set
            def deadline = System.currentTimeMillis() + (pveHostProp('pve.api.budget.ms', '45000') as long)
            def lists = running.findAll { it.node.toString() in online }
                .collect { [it.node.toString(), it.type.toString()] }.unique().sort { a, b -> a[0] <=> b[0] ?: a[1] <=> b[1] }
            def skipped = 0
            lists.each { node, type ->
                if (System.currentTimeMillis() > deadline) { skipped++; return }
                try {
                    (pveGet('/nodes/' + node + '/' + type + (type == 'qemu' ? '?full=1' : '')) ?: []).each { row ->
                        if (row.vmid != null) { guestDetail[type + '/' + row.vmid] = [node: node, row: row] }
                    }
                    detailNodes << node
                } catch (Exception listFailure) {
                    System.err.println('Guest pressure and disk figures: no ' + type + ' list from node ' + node + ': ' + listFailure.message)
                }
            }
            if (skipped) {
                System.err.println('Guest pressure and disk figures: time budget reached (pve.api.budget.ms): ' + skipped + ' node lists not read')
            }
        } catch (Exception statusFailure) {
            System.err.println('Guest pressure and disk figures: /cluster/status unreadable: ' + statusFailure.message)
        }
    }

    /*
     * Disk latency is the change in blockstat's total time over the change in operations since
     * the previous poll, so LogicMonitor needs no complex datapoint. The previous totals are
     * kept in lm.cache, one entry per node; the first poll, a guest new to a node (a migration),
     * counters that went backwards (a restart) and an interval without I/O all withhold it.
     */
    def ioKey = { String node -> 'iolatency:' + (pveHostProp('pve.cluster') ?: '') + ':' + node }
    def ioBefore = detailNodes.collectEntries { node ->
        def saved = null
        try {
            def text = pveState().read(ioKey(node))
            saved = text ? new groovy.json.JsonSlurper().parseText(text.toString()) : null
        } catch (Exception unreadable) { }
        [node, saved instanceof Map ? saved : [:]]
    }
    def ioNow = detailNodes.collectEntries { [it, [:]] }
    def latencyMs = { now, before, int time, int ops ->
        if (!(before instanceof List) || before.size() < 4) { return null }
        def operations = now[ops] - (before[ops] as double)
        def elapsed = now[time] - (before[time] as double)
        operations > 0 && elapsed >= 0 ? pveRound(elapsed / operations / 1000000.0d) : null
    }

    guests.each { guest ->
        def id = guest.id

        pveEmit(id, 'CPUUsagePercent', pveRound(((guest.cpu ?: 0) as double) * 100.0d))
        pveEmit(id, 'CPUCount', guest.maxcpu ?: 0)

        pveEmit(id, 'MemoryUsedMB', pveMB(guest.mem))
        pveEmit(id, 'MemoryCapacityMB', pveMB(guest.maxmem))
        pveEmit(id, 'MemoryUsagePercent', pvePercent(guest.mem, guest.maxmem))

        pveEmit(id, 'DiskCapacityGB', pveGB(guest.maxdisk))

        /*
         * memhost is the host's view of a VM's memory footprint; mem is the guest's own.
         * Proxmox reports it for qemu only and sends 0 for every container, and older
         * Proxmox omits it, so it is withheld in both cases rather than reported as zero.
         */
        if (guest.type == 'qemu' && guest.memhost != null) {
            pveEmit(id, 'MemoryHostMB', pveMB(guest.memhost))
        }

        def detail = guestDetail[guest.id?.toString()]
        def row = detail?.row
        if (row instanceof Map) {
            // Linux pressure stall information for the guest's cgroup: Proxmox's 10-second average.
            def cpuSome = number(row.pressurecpusome)
            def cpuFull = number(row.pressurecpufull)
            def ioSome = number(row.pressureiosome)
            def ioFull = number(row.pressureiofull)
            def memorySome = number(row.pressurememorysome)
            def memoryFull = number(row.pressurememoryfull)
            if (cpuSome != null) { pveEmit(id, 'PressureCPUSomePercent', pveRound(cpuSome)) }
            if (cpuFull != null) { pveEmit(id, 'PressureCPUFullPercent', pveRound(cpuFull)) }
            if (ioSome != null) { pveEmit(id, 'PressureIOSomePercent', pveRound(ioSome)) }
            if (ioFull != null) { pveEmit(id, 'PressureIOFullPercent', pveRound(ioFull)) }
            if (memorySome != null) { pveEmit(id, 'PressureMemorySomePercent', pveRound(memorySome)) }
            if (memoryFull != null) { pveEmit(id, 'PressureMemoryFullPercent', pveRound(memoryFull)) }

            /*
             * ballooninfo, VMs only: actual is the memory the guest has now, max_mem what it is
             * configured with; the difference is what the balloon has taken back. The swap and
             * major-fault figures are the guest's own statistics, cumulative counters, present
             * only while its balloon driver reports them.
             */
            def balloon = row.ballooninfo instanceof Map ? row.ballooninfo : null
            if (balloon != null) {
                def actual = number(balloon.actual)
                def configured = number(balloon.max_mem)
                if (actual != null && configured != null) {
                    pveEmit(id, 'BalloonMB', pveMB(Math.max(0.0d, configured - actual)))
                }
                if (number(balloon.mem_swapped_in) != null) { pveEmit(id, 'GuestSwapInRateMB', pveMB(balloon.mem_swapped_in)) }
                if (number(balloon.mem_swapped_out) != null) { pveEmit(id, 'GuestSwapOutRateMB', pveMB(balloon.mem_swapped_out)) }
                if (number(balloon.major_page_faults) != null) {
                    pveEmit(id, 'MajorPageFaultRate', number(balloon.major_page_faults) as long)
                }
            }

            // blockstat, VMs only: QEMU's counters per disk, summed over the guest's disks.
            def blocks = row.blockstat instanceof Map ? row.blockstat : null
            if (blocks != null) {
                def totals = [0.0d, 0.0d, 0.0d, 0.0d]
                blocks.values().findAll { it instanceof Map }.each { disk ->
                    ['rd_total_time_ns', 'rd_operations', 'wr_total_time_ns', 'wr_operations'].eachWithIndex { field, i ->
                        totals[i] += number(disk[field]) ?: 0.0d
                    }
                }
                pveEmit(id, 'DiskReadIOPS', totals[1] as long)
                pveEmit(id, 'DiskWriteIOPS', totals[3] as long)
                def vmid = guest.vmid?.toString()
                if (vmid) {
                    ioNow[detail.node][vmid] = totals
                    def before = ioBefore[detail.node]?.get(vmid)
                    def read = latencyMs(totals, before, 0, 1)
                    def write = latencyMs(totals, before, 2, 3)
                    if (read != null) { pveEmit(id, 'DiskReadLatencyMs', read) }
                    if (write != null) { pveEmit(id, 'DiskWriteLatencyMs', write) }
                }
            }
        }

        /*
         * Converted but still cumulative: these stay counters, and scaling a counter is
         * monotonic, so the derive rate LogicMonitor computes is simply MB per second.
         */
        pveEmit(id, 'DataRateRxMB', pveMB(guest.netin))
        pveEmit(id, 'DataRateTxMB', pveMB(guest.netout))
        pveEmit(id, 'DiskReadRateMB', pveMB(guest.diskread))
        pveEmit(id, 'DiskWriteRateMB', pveMB(guest.diskwrite))

        /*
         * Only LXC reports used disk. QEMU exposes maxdisk but never disk -- real usage
         * inside a VM needs the guest agent, exactly as VMware needs VMware Tools. These
         * datapoints are deliberately not emitted for QEMU so they read as "no data"
         * rather than a confident and wrong zero.
         */
        if (guest.type == 'lxc') {
            pveEmit(id, 'DiskUsedGB', pveGB(guest.disk))
            pveEmit(id, 'DiskUsagePercent', pvePercent(guest.disk, guest.maxdisk))
        }
    }

    // An hour outlasts a missed poll or two; a stale entry only withholds one latency figure.
    ioNow.each { node, totals ->
        try {
            pveState().write(ioKey(node), groovy.json.JsonOutput.toJson(totals), 3600)
        } catch (Exception unwritable) { }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox guest performance collection failed: ' + exception.message)
    return 2
}
