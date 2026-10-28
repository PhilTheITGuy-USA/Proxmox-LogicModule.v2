/*
 * Active Discovery: this node's bridges -- the Linux or Open vSwitch virtual switches its
 * guests attach to -- asked of the node itself.
 *
 * The wildvalue is the bridge name. Each instance carries the vertex
 * Proxmox_VE_Cluster_Topology draws between the node and its guests, type VirtualSwitch.
 * Physical ports, bonds and VLAN interfaces are not discovered: the node's SNMP interface
 * monitoring covers them, and Proxmox gives no interface MAC a switch could join on.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodeName = pveSelfNode()
    def cluster = pveCarrierCluster('Bridge', true)
    pveNetBridges(pveNodeGet('/nodes/' + nodeName + '/network')).each { bridge ->
        def name = bridge.iface.toString()
        def ports = [bridge.bridge_ports, bridge.ovs_ports].findAll { it }.join(' ').trim()
        pveDiscover(
            name,
            name + ' (' + bridge.type + (bridge.cidr ? ', ' + bridge.cidr : '') + ')',
            'Bridge ' + name + ' on ' + nodeName + (ports ? ', over ' + ports : ''),
            [
                pve_node      : nodeName,
                pve_iface     : name,
                pve_iface_type: bridge.type,
                pve_ports     : ports,
                pve_cidr      : bridge.cidr,
                pve_vlan_aware: bridge.bridge_vlan_aware ? 'true' : null
            ],
            cluster ? pveEri(pveTopoVertexKey(cluster, ['net', nodeName, name]), 'VirtualSwitch') : [:]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox bridge discovery failed: ' + exception.message)
    return 2
}
