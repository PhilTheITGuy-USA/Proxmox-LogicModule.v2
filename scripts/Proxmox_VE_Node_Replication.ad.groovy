/*
 * Active Discovery: storage replication jobs.
 *
 * Job status is exposed per node at /nodes/{node}/replication, which lists the jobs whose
 * source is that node. The job id ("100-0", guest and job number) is cluster-unique, so it
 * is used as the wildvalue on its own -- deliberately without the node name. A replication
 * job follows its guest, and an id built from the current node would be destroyed by a
 * migration, exactly as it would for a guest instance.
 *
 * Jobs are deduplicated by id: a job appearing from more than one node must not become two
 * instances.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {

    def seen = [] as Set

    def nodeName = pveSelfNode()

    /*
     * A node that runs no replication answers an empty list, not an error, so a failed
     * read fails the run: a run that answered "no jobs" instead would retire every job
     * instance (the module auto-deletes, spec A§3.2). The list is filtered by the token's
     * VM.Audit on each guest, so an empty one is checked with pveListHidden as well.
     */
    def jobs = pveNodeGet('/nodes/' + nodeName + '/replication') ?: []
    if (jobs.isEmpty() && pveListHidden(pveNodeGet, '/vms', 'VM.Audit')) {
        System.err.println(pveHiddenReason('The replication jobs of ' + nodeName, '/vms', 'VM.Audit'))
        return 2
    }

    jobs.each { job ->
        def id = job.id?.toString()
        if (!id || seen.contains(id)) { return }
        seen << id

        def guest = job.guest?.toString()
        pveDiscover(
            id,
            'Replication ' + id,
            'Replication of guest ' + guest + ' to ' + (job.target ?: 'unknown'),
            [
                pve_job     : id,
                pve_guest   : guest,
                pve_target  : job.target,
                pve_source  : job.source,
                pve_schedule: job.schedule,
                pve_type    : job.type
            ]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox replication discovery failed: ' + exception.message)
    return 2
}
