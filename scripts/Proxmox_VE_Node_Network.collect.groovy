/*
 * BatchScript collection: state of this node's bridges, and of the VLANs its guest NICs use
 * on them, which take their bridge's Active and Healthy (A§3.4, amended 2026-10-30). Discovery
 * found the VLANs in the guest configs; collection learns them from the instances LogicMonitor
 * hands a BatchScript, so a poll stays one /network call.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodeName = pveSelfNode()
    def bridges = pveNetBridges(pveNodeGet('/nodes/' + nodeName + '/network'))
    def emitState = { String id, Map bridge ->
        def active = bridge.active?.toString() == '1'
        def autostart = bridge.autostart?.toString() == '1'
        pveEmit(id, 'Active', active ? 1 : 0)
        // Down only where it should be up: configured to start at boot, yet not active.
        pveEmit(id, 'Healthy', autostart && !active ? 0 : 1)
    }
    bridges.each { bridge ->
        def id = bridge.iface.toString()
        emitState(id, bridge)
        pveEmit(id, 'Ports', [bridge.bridge_ports, bridge.ovs_ports].findAll { it }.join(' ').tokenize(' ').size())
    }
    // A VLAN whose bridge is gone reports nothing; its id is rebuilt from its properties
    // where the instance does not name its wildvalue.
    pveBatchInstances().findAll { it.prop('pve_iface_type') == 'vlan' && it.prop('pve_iface') && it.prop('pve_vlan') }.each { vlan ->
        def bridge = bridges.find { it.iface.toString() == vlan.prop('pve_iface') }
        if (bridge) { emitState(vlan.wildvalue ?: vlan.prop('pve_iface') + '-vlan' + vlan.prop('pve_vlan'), bridge) }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox bridge collection failed: ' + exception.message)
    return 2
}
