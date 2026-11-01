/*
 * BatchScript collection: reruns the troubleshooter's checks and prints, for each discovered
 * problem, Problem 1 while it persists and 0 once it is gone, so its alert clears on the value
 * rather than on the instance's deletion. A problem whose check the chain did not reach this
 * time (an earlier check failed) prints nothing. A failover-<address> problem is resolved
 * whenever the failover stage ran without it, the address fixed or taken out of pve.api.hosts.
 */
try {
    def result = pveTroubleshoot(pveTroubleProbes())
    def open = result.findings*.id as Set
    pveBatchInstances().each { instance ->
        def id = instance.wildvalue
        if (!id) { return }
        def checked = id in result.reached || (id.startsWith('failover-') && 'failover' in result.reached)
        if (!checked) { return }
        pveEmit(id, 'Problem', open.contains(id) ? 1 : 0)
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox troubleshooter collection failed: ' + exception.message)
    return 2
}
