/*
 * BatchScript collection: whether each QEMU virtual machine is really running.
 *
 * A node's own VM list reads "running" for as long as the QEMU process exists, so two kinds
 * of hang leave the VM looking up there:
 *
 *   Paused           QEMU has stopped the VM's CPUs while Proxmox still says running: its
 *                    storage filled or failed (io-error), the guest panicked, or someone
 *                    paused it. 1 when QEMU's own state (qmpstatus, from /nodes/<n>/qemu?full=1,
 *                    one call per node) is anything but running and no task holds a lock on
 *                    the VM -- a suspend-mode backup, a migration or a snapshot pauses it on
 *                    purpose. 0 for a VM that is not running: Guest Status covers that.
 *                    /cluster/resources is not used for this: it calls a paused VM "paused",
 *                    not "running" (lab, qm suspend, 2026-11-05), so its status only says
 *                    which VMs are stopped; the node's own list decides the rest.
 *   AgentResponding  The VM's operating system is hung while QEMU runs on. 1 when its guest
 *                    agent answers /agent/info, 0 when Proxmox answers HTTP 500, as it does for
 *                    an agent that does not respond. Asked only of a VM discovery recorded with
 *                    the agent enabled (auto.pve_agent), that is running, unlocked, not
 *                    paused (Paused already alerts: one alert, not two) and up for at least
 *                    AGENT_GRACE_SECONDS, so a VM still booting is not asked.
 *
 * Withheld, never guessed: a VM on a node that is down (the node alerts), a node whose VM
 * list was not read, a VM not yet in its node's list (a migration landing), and an agent read
 * that timed out, was refused (HTTP 403: the token lacks VM.GuestAgent.Audit, or VM.Monitor
 * on Proxmox VE 8) or was skipped by the per-node breaker or the time budget.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}
// Only the resource that holds the cluster role now collects; the other prints nothing.
if (!pveRole('Proxmox_VE_Cluster_VMHealth').collect) {
    return 0
}

def AGENT_GRACE_SECONDS = 180

try {
    def vms = pveGuestList().findAll { it.type == 'qemu' }
    def statusRows = pveGet('/cluster/status') ?: []
    def online = statusRows.findAll { it.type?.toString() == 'node' && it.name && (it.online == null || it.online?.toString() == '1') }
        .collect { it.name.toString() } as Set
    // Each VM's agent option as discovery recorded it, by folded wildvalue.
    def agentEnabled = pveBatchInstances().findAll { it.wildvalue }.collectEntries { known ->
        [(pveWildValue(known.wildvalue)): known.prop('pve_agent')]
    }

    // Each VM's row in its node's own list (status and QEMU's state), one list per online node
    // with a VM that is not stopped, inside the time budget.
    def qemuRows = [:]
    def listed = [] as Set
    def deadline = System.currentTimeMillis() + (pveHostProp('pve.api.budget.ms', '45000') as long)
    vms.findAll { it.status?.toString() != 'stopped' && it.node?.toString() in online }
        .collect { it.node.toString() }.unique().sort().each { node ->
            if (System.currentTimeMillis() > deadline) {
                System.err.println('QEMU state: time budget reached (pve.api.budget.ms) before node ' + node + "'s VM list")
                return
            }
            try {
                (pveGet('/nodes/' + node + '/qemu?full=1') ?: []).each { row ->
                    if (row.vmid != null) { qemuRows[row.vmid.toString()] = row }
                }
                listed << node
            } catch (Exception listFailure) {
                System.err.println('QEMU state: no VM list from node ' + node + ': ' + listFailure.message)
            }
        }

    def askAgent = []
    vms.each { vm ->
        def node = vm.node?.toString()
        if (!(node in online)) { return }
        if (vm.status?.toString() == 'stopped') {
            pveEmit(vm.id, 'Paused', 0)
            return
        }
        def row = node in listed ? qemuRows[vm.vmid.toString()] : null
        if (row == null) { return }
        if (row.status?.toString() != 'running') {
            pveEmit(vm.id, 'Paused', 0)
            return
        }
        def state = row.qmpstatus?.toString()
        if (state == null) { return }
        pveEmit(vm.id, 'Paused', !vm.lock && state != 'running' ? 1 : 0)
        if (agentEnabled[pveWildValue(vm.id)] == '1' && !vm.lock && state == 'running' &&
                ((vm.uptime ?: 0) as long) >= AGENT_GRACE_SECONDS) {
            askAgent << vm
        }
    }

    /*
     * One agent read per VM, through pveForEachGuest: asked of the VM's own node, bounded in
     * concurrency and time. An HTTP error does not trip the node's breaker (false): a dead
     * agent answers 500 for its own VM alone.
     */
    int refused = 0
    pveForEachGuest('Guest agent checks', askAgent, statusRows, '/agent/info', { vm, data, error ->
        def status = error == null ? null : pveFailureStatus(error)
        if (status == 403) {
            refused++
            return
        }
        // A timeout, a stopped breaker or the budget says nothing about the agent.
        if (error != null && status != 500) { return }
        pveEmit(vm.id, 'AgentResponding', error == null ? 1 : 0)
    }, false)
    if (refused) {
        System.err.println(refused + ' guest agent checks refused (HTTP 403): the token lacks VM.GuestAgent.Audit ' +
                           '(VM.Monitor on Proxmox VE 8), so AgentResponding is withheld for those VMs.')
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox VM health collection failed: ' + exception.message)
    return 2
}
