/*
 * BatchScript collection: reruns the troubleshooter's checks and prints, for each discovered
 * problem, Problem 1 while it persists and 0 otherwise, so its alert clears on the value rather
 * than on the instance's deletion. A problem whose check the chain did not reach this time (an
 * earlier check failed, the resource changed kind, a failover address left pve.api.hosts)
 * prints 0 too: the earlier failure is the one problem shown, and a problem still there returns
 * once the chain reaches it again (decision I2, user 2026-11-01).
 */
try {
    def open = pveTroubleshoot(pveTroubleProbes()).findings*.id as Set
    pveBatchInstances().each { instance ->
        def id = instance.wildvalue
        if (id) { pveEmit(id, 'Problem', open.contains(id) ? 1 : 0) }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox troubleshooter collection failed: ' + exception.message)
    return 2
}
