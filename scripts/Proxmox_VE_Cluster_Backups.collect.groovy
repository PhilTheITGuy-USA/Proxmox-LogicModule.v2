/*
 * BatchScript collection: each guest's last backup and failed backups, per backup job.
 *
 * The results come from vzdump run logs, read by pveBackupResults in the preamble, which
 * discovery reads the same way; its comment says how.
 *
 * LastBackupAgeHours and LastBackupSizeGB come from the guest's newest successful run;
 * FailedSinceLastBackup is 1 when a newer run to the same storage failed for the guest --
 * by name in its log, or by failing before it reached the guest. Times come from the task,
 * not the log text. If any online node's task list or any needed run's log cannot be read,
 * those three are withheld for every guest: the missing run may hold a guest's newest
 * result, and a guess would be worse than a gap.
 *
 * A failure's reason, node and time reach the alert only through the instance description,
 * which discovery writes (every 15 minutes) and collection cannot. So FailedSinceLastBackup
 * reads 1 once discovery has recorded this failure -- the instance's pve_backup_failed_at is
 * the failed run's start -- and is withheld until then, so the alert never opens without its
 * reason. A failure whose run ended more than RECORD_WAIT_SECONDS ago reads 1 regardless:
 * a discovery that cannot run must not hold the alert back for good.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}
// Only the resource that holds the cluster role now collects; the other prints nothing.
if (!pveRole('Proxmox_VE_Cluster_Backups').collect) {
    return 0
}

final long RECORD_WAIT_SECONDS = 3600L

try {
    def instances = pveBackupInstances()
    long now = System.currentTimeMillis().intdiv(1000L)
    def results = pveBackupResults(instances)
    // What discovery last recorded on each instance, by folded wildvalue.
    def recorded = pveBatchInstances().findAll { it.wildvalue }.collectEntries { known ->
        [(pveWildValue(known.wildvalue)): known.prop('pve_backup_failed_at')]
    }

    instances.each { instance ->
        def id = instance.id
        pveEmit(id, 'NotCovered', instance.covered ? 0 : 1)
        if (!instance.covered) { return }

        pveEmit(id, 'JobEnabled', instance.enabled ? 1 : 0)
        pveEmit(id, 'IncludedVolumes', instance.included.size())
        pveEmit(id, 'ExcludedVolumes', instance.excluded.size())
        if (instance.nextRun != null) {
            pveEmit(id, 'HoursUntilNextRun',
                pveRound(((instance.nextRun as double) - (now as double)) / 3600.0d))
        }
        if (!results.complete) { return }

        def state = pveBackupState(instance, results)
        if (state.newest) {
            pveEmit(id, 'LastBackupAgeHours',
                pveRound(((now as double) - (state.newest.start as double)) / 3600.0d))
            if (state.newest.bytes != null) {
                pveEmit(id, 'LastBackupSizeGB', pveGB(state.newest.bytes))
            }
        }

        def failure = state.failure
        def isRecorded = failure && recorded[pveWildValue(id)] == failure.start.toString()
        def settled = failure && failure.end != null && now - (failure.end as long) > RECORD_WAIT_SECONDS
        if (!failure || isRecorded || settled) {
            pveEmit(id, 'FailedSinceLastBackup', failure ? 1 : 0)
        }
        if (failure) {
            System.err.println('Backup of ' + (instance.name ?: instance.vmid) + ' (' + instance.vmid + ')' +
                (instance.storage ? ' to ' + instance.storage : '') +
                (isRecorded || settled ? ' has failed since its last good backup: ' :
                    ' failed; reported once discovery records why: ') + failure.reason)
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox backup collection failed: ' + exception.message)
    return 2
}
