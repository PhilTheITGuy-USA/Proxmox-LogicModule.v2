/*
 * Active Discovery: QEMU virtual machines, cluster-wide, for Proxmox_VE_Cluster_VMHealth.
 * The same instances and wildvalues (qemu/<vmid>) as the guest modules, less the containers:
 * a container has no QEMU to pause and no guest agent.
 *
 * What it adds, from one config read per VM by the collecting resource:
 *
 * - auto.pve_agent, 1 when the VM's QEMU guest agent option is enabled (agent "1", or
 *   "enabled=1" with other settings after it), else 0. Only a VM with it enabled is asked
 *   whether its agent answers: one without it has nothing to answer.
 *
 * A VM whose config was not read -- a gating call failed, its node is offline, or its node's
 * reads stopped after a failure -- is still discovered, without it, and collection then leaves
 * its agent alone. Only /cluster/resources?type=vm failing, or answering no guests (an
 * under-privileged token), exits 2.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def vms = pveGuestList().findAll { it.type == 'qemu' }

    // Only the collecting resource reads configs; the other has no collection to use them.
    def readConfigs = pveRole().collect
    def statusRows = []
    if (readConfigs) {
        try {
            statusRows = pveGet('/cluster/status') ?: []
        } catch (Exception gateException) {
            System.err.println('VM configs not read: ' + gateException.message)
            readConfigs = false
        }
    }
    if (!readConfigs) {
        vms.each { vm -> pveGuestDiscover(vm) }
        return 0
    }

    pveForEachGuest('Config reads', vms, statusRows, '/config', { vm, config, error ->
        def extra = [:]
        if (config instanceof Map) {
            extra.pve_agent = config.agent?.toString() ==~ /^(1|enabled=1)(,.*)?$/ ? '1' : '0'
        }
        pveGuestDiscover(vm, [:], extra)
    })
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox VM discovery failed: ' + exception.message)
    return 2
}
