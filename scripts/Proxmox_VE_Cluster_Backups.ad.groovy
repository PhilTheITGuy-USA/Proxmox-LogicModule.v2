/*
 * Active Discovery: backup jobs and their guests, for Proxmox_VE_Cluster_Backups.
 *
 * One instance per guest per job, grouped by job: auto.pve_backup_job is the job's comment,
 * or its id when it has none. A guest in two jobs has an instance under each. Guests that
 * no job covers are instances too, in the group "No backup job", so each one can be seen
 * and alerted on by name. Each job instance lists the guest's volumes the job backs up and
 * the ones it leaves out, with the reason, as properties. pveBackupInstances in the preamble builds the list, the same one
 * collection uses.
 *
 * The description names the job and, while the guest has failed since its last good backup,
 * the failed run: when it started, on which node and why. The alerts print the description
 * (##DSIDESCRIPTION##), the only way text reaches them, so whoever gets the alert knows
 * which guest failed and why without searching task logs. auto.pve_backup_failed_at holds
 * the failed run's start, which tells collection the reason is now recorded. The results come
 * from pveBackupResults, as collection's do. If they cannot all be read, the description
 * names the job alone: the missing run may hold the guest's newest result.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

// A reason is one log line, but a run's status can be long; the description stays readable.
final int REASON_LENGTH = 200

try {
    def instances = pveBackupInstances()
    // Every guest is either in a job or not backed up, so an empty answer is a cluster
    // without guests, or job members and uncovered guests the token may not see (lab
    // 2026-11-02): those are kept, not retired.
    if (instances.isEmpty() && pveListHidden(pveGet, '/vms', 'VM.Audit')) {
        System.err.println(pveHiddenReason('The guests of every backup job and the guests no job covers', '/vms', 'VM.Audit'))
        return 2
    }

    // Failures are extra detail: without them every instance is still discovered.
    def results = null
    try {
        results = pveBackupResults(instances)
    } catch (Exception resultsException) {
        System.err.println('Backup failures not named in descriptions: ' + resultsException.message)
    }
    if (results != null && !results.complete) { results = null }
    def clock = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm z', Locale.ROOT)

    instances.each { instance ->
        def guest = (instance.name ?: (instance.type + '-' + instance.vmid)) + ' (' + instance.vmid + ')'
        def failure = (instance.covered && results != null) ? pveBackupState(instance, results).failure : null
        def description = instance.covered ? 'Backed up by ' + instance.job : 'Covered by no backup job'
        if (failure) {
            def reason = failure.reason.length() > REASON_LENGTH ?
                failure.reason.substring(0, REASON_LENGTH - 3) + '...' : failure.reason
            description += '. Last failure in the run started ' + clock.format(new Date((failure.start as long) * 1000L)) +
                ' on ' + failure.node + ': ' + reason
        }
        pveDiscover(
            instance.id,
            guest,
            description,
            [
                pve_backup_job     : instance.job,
                pve_backup_job_id  : instance.jobId,
                pve_backup_schedule: instance.schedule,
                pve_backup_storage : instance.storage,
                pve_vmid           : instance.vmid,
                pve_type           : instance.type,
                pve_backup_included_volumes: instance.included?.join(', '),
                pve_backup_excluded_volumes: instance.excluded?.join(', '),
                pve_backup_failed_at: failure?.start
            ]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox backup discovery failed: ' + exception.message)
    return 2
}
