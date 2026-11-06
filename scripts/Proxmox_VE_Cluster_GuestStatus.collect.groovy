/*
 * BatchScript collection: guest power, lock and HA state.
 *
 * Split from performance because it wants a different alert posture: status flaps are
 * interesting immediately, whereas a CPU spike usually needs a trigger interval.
 *
 * StartAtBootNotRunning alerts on a guest that should be running and is not. Status ships
 * without a threshold, because guests are often stopped on purpose; Proxmox's own statement
 * of intent is "Start at boot" (onboot), which discovery reads from each guest's config and
 * stamps as auto.pve_onboot, and collection reads back from the instances the Collector
 * hands over (datasourceinstanceProps). It is 1 for a guest set to start at boot that is not
 * running, unless something else explains it or already alerts:
 *
 *   locked      a task holds the guest (a backup in stop mode, a migration, a snapshot or
 *               rollback, a suspend): it is meant to be down for now.
 *   HA-managed  HA starts and stops it, not onboot; HAError alerts when HA cannot.
 *   node down   its node is not online, which alerts on its own: a guest must not repeat
 *               its parent's alert.
 *
 * A guest whose onboot discovery has not recorded (no binding, or its config not read) gets
 * no value: an unknown intent must not page.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}
// Only the resource that holds the cluster role now collects; the other prints nothing.
if (!pveRole('Proxmox_VE_Cluster_GuestStatus').collect) {
    return 0
}

// HA states that mean the cluster is actively unhappy about this guest, as opposed to
// merely having it stopped on purpose.
def haErrorStates = ['error', 'fence']

try {
    def guests = (pveGet('/cluster/resources?type=vm') ?: [])
        .findAll { it.type in ['qemu', 'lxc'] && !it.template }
    def onlineNodes = (pveGet('/cluster/resources?type=node') ?: [])
        .findAll { it.status?.toString() == 'online' }
        .collect { it.node?.toString() } as Set
    // Each guest's onboot as discovery recorded it, by folded wildvalue.
    def onboot = pveBatchInstances().findAll { it.wildvalue }.collectEntries { known ->
        [(pveWildValue(known.wildvalue)): known.prop('pve_onboot')]
    }

    guests.each { guest ->
        def id = guest.id
        def status = guest.status?.toString() ?: ''
        def haState = guest.hastate?.toString() ?: ''

        pveEmit(id, 'Status', status == 'running' ? 1 : 0)
        pveEmit(id, 'UpTimeSeconds', guest.uptime ?: 0)
        pveEmit(id, 'Locked', guest.lock ? 1 : 0)
        // Proxmox holds the "migrate" lock for the whole of a migration.
        pveEmit(id, 'Migrating', guest.lock?.toString() == 'migrate' ? 1 : 0)
        pveEmit(id, 'HAManaged', haState ? 1 : 0)
        pveEmit(id, 'HAError', haErrorStates.contains(haState) ? 1 : 0)

        def startAtBoot = onboot[pveWildValue(id)]
        if (startAtBoot != null) {
            // Only "stopped" is down. /cluster/resources names a VM QEMU has stopped by why it stopped
            // ("paused", "guest-panicked", "io-error"), and Proxmox_VE_Cluster_VMHealth alerts on those (Paused).
            def down = startAtBoot == '1' && status == 'stopped' && !guest.lock && !haState &&
                onlineNodes.contains(guest.node?.toString())
            pveEmit(id, 'StartAtBootNotRunning', down ? 1 : 0)
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox guest status collection failed: ' + exception.message)
    return 2
}
