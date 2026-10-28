/*
 * Active Discovery: this node's network interfaces -- bridges, bonds, physical ports and VLAN
 * interfaces, Linux or Open vSwitch -- asked of the node itself.
 *
 * The wildvalue is the interface name. Each instance carries the vertex
 * Proxmox_VE_Cluster_Topology draws between the node and its guests: VirtualSwitch for a
 * bridge, PhysicalNIC for a port or bond, NICVIRTUAL for a VLAN interface.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodeName = pveSelfNode()
    def cluster = pveCarrierCluster('Network interface', true)
    pveNetIfaces(pveNodeGet('/nodes/' + nodeName + '/network')).each { iface ->
        def name = iface.iface.toString()
        def type = iface.type.toString()
        def ports = [iface.bridge_ports, iface.slaves, iface.ovs_ports, iface.ovs_bonds].findAll { it }.join(' ').trim()
        pveDiscover(
            name,
            name + ' (' + type + (iface.cidr ? ', ' + iface.cidr : '') + ')',
            type + ' ' + name + ' on ' + nodeName + (ports ? ', over ' + ports : ''),
            [
                pve_node      : nodeName,
                pve_iface     : name,
                pve_iface_type: type,
                pve_ports     : ports,
                pve_cidr      : iface.cidr,
                pve_vlan_aware: iface.bridge_vlan_aware ? 'true' : null
            ],
            cluster ? pveEri(pveTopoVertexKey(cluster, ['net', nodeName, name]), pveNetErt(type)) : [:]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox network discovery failed: ' + exception.message)
    return 2
}
