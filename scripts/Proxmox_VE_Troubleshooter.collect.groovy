/*
 * BatchScript collection: reruns the troubleshooter's checks and prints Problem 1 for each
 * problem still open and 0 for every other, so an alert clears on the value rather than on the
 * instance's deletion. A problem whose check the chain did not reach this time (an earlier check
 * failed, the resource changed kind, a failover address left pve.api.hosts) prints 0 too: the
 * earlier failure is the one problem shown, and a problem still there returns once the chain
 * reaches it again (decision I2, user 2026-11-01).
 *
 * It prints every check the chain knows, not only the instances datasourceinstanceProps hands
 * over: on the lab Problem read No Data on a new instance, the binding lacking it or its
 * wildvalue (2026-11-01, not told apart). The binding adds only the failover-<address> instances whose address has
 * left pve.api.hosts, by the auto.pve_check discovery stamps where the wildvalue is missing.
 */
try {
    def result = pveTroubleshoot(pveTroubleProbes())
    def open = result.findings*.id as Set
    def ids = ['settings', 'address', 'ping', 'certificate', 'token', 'permissions', 'node'] as LinkedHashSet
    ids.addAll(open)
    ids.addAll(result.reached.findAll { it.startsWith('failover-') })
    pveBatchInstances().each { instance -> ids << (instance.wildvalue ?: instance.prop('pve_check')) }
    ids.findAll { it }.each { id -> pveEmit(id, 'Problem', open.contains(id) ? 1 : 0) }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox troubleshooter collection failed: ' + exception.message)
    return 2
}
