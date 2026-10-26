/*
 * BatchScript collection: each guest's snapshots -- how many, how old the oldest is, and how
 * many saved a VM's RAM as well as its disks.
 *
 * One /snapshot call per guest on an online node, so this module runs hourly: it is one of
 * the suite's O(guests) costs, and snapshot counts change slowly. A guest on an offline node
 * cannot be asked and reports nothing until its node returns.
 *
 * Every list carries an entry named "current", which stands for the running state and is not
 * a snapshot; it is never counted. A snapshot's snaptime is Unix seconds.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}
// Only the resource that holds the cluster role now collects; the other prints nothing.
if (!pveRole('Proxmox_VE_Cluster_Snapshots').collect) {
    return 0
}

try {
    def guests = pveGuestList()
    def statusRows = pveGet('/cluster/status') ?: []
    def nowSeconds = System.currentTimeMillis() / 1000.0d

    /*
     * One snapshot list per guest, through pveForEachGuest: asked of the node that owns the
     * guest, never of an offline node, stopped for a node after its first failure, bounded in
     * concurrency and time. A guest whose list was not read reports nothing this run.
     */
    pveForEachGuest('Snapshot reads', guests, statusRows, '/snapshot', { guest, data, error ->
        if (error != null) { return }
        def entries = data ?: []

        def snapshots = entries.findAll { it instanceof Map && it.name?.toString() != 'current' }
        def id = guest.id
        pveEmit(id, 'SnapshotCount', snapshots.size())

        // No snapshots means no age: a 0 would read as a snapshot taken just now.
        def times = snapshots.findAll { it.snaptime != null }.collect { it.snaptime.toString().toDouble() }
        if (times) {
            pveEmit(id, 'OldestSnapshotAgeDays', pveRound((nowSeconds - times.min()) / 86400.0d))
        }
        // Only a VM can save its RAM; a container has no vmstate to report.
        if (guest.type == 'qemu') {
            pveEmit(id, 'SnapshotsWithRAM', snapshots.count { it.vmstate?.toString() in ['1', 'true'] })
        }
    })
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox snapshot collection failed: ' + exception.message)
    return 2
}
