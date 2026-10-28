/*
 * BatchScript collection: state of this node's bridges.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodeName = pveSelfNode()
    pveNetBridges(pveNodeGet('/nodes/' + nodeName + '/network')).each { bridge ->
        def id = bridge.iface.toString()
        def active = bridge.active?.toString() == '1'
        def autostart = bridge.autostart?.toString() == '1'
        pveEmit(id, 'Active', active ? 1 : 0)
        // Down only where it should be up: configured to start at boot, yet not active.
        pveEmit(id, 'Healthy', autostart && !active ? 0 : 1)
        pveEmit(id, 'Ports', [bridge.bridge_ports, bridge.ovs_ports].findAll { it }.join(' ').tokenize(' ').size())
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox bridge collection failed: ' + exception.message)
    return 2
}
