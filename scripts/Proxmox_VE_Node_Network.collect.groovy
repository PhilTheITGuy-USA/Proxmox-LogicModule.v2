/*
 * BatchScript collection: state of this node's network interfaces.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodeName = pveSelfNode()
    pveNetIfaces(pveNodeGet('/nodes/' + nodeName + '/network')).each { iface ->
        def id = iface.iface.toString()
        def active = iface.active?.toString() == '1'
        def autostart = iface.autostart?.toString() == '1'
        pveEmit(id, 'Active', active ? 1 : 0)
        // Down only where it should be up: configured to start at boot, yet not active.
        pveEmit(id, 'Healthy', autostart && !active ? 0 : 1)
        if (iface.type.toString() in ['bridge', 'bond', 'OVSBridge', 'OVSBond']) {
            def ports = [iface.bridge_ports, iface.slaves, iface.ovs_ports, iface.ovs_bonds].findAll { it }.join(' ')
            pveEmit(id, 'Ports', ports.tokenize(' ').size())
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox network collection failed: ' + exception.message)
    return 2
}
