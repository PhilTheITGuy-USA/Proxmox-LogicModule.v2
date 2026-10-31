/*
 * Active Discovery: this node's bridges -- the Linux or Open vSwitch virtual switches its
 * guests attach to -- and the VLANs its guests' NICs use on them, asked of the node itself.
 *
 * Wildvalues: the bridge name, and "<bridge>-vlan<N>" for a VLAN. Both carry the vertex
 * Proxmox_VE_Cluster_Topology draws them as, type VirtualSwitch: node -> bridge -> guest, or
 * node -> bridge -> VLAN -> guest for a tagged NIC (A§3.4, amended 2026-10-30). A VLAN is
 * this node's alone: Proxmox cannot say that VLAN 30 on two nodes is one segment. A name that
 * is an SDN VNet is left to Proxmox_VE_Cluster_SDN. Physical ports, bonds and host VLAN
 * interfaces are not discovered: the node's SNMP interface monitoring covers them. A VLAN no
 * guest uses any more keeps its instance, as everything this suite discovers does, and is
 * deleted by hand.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodeName = pveSelfNode()
    def cluster = pveCarrierCluster('Bridge and VLAN', true)
    def vnetNames = []
    try {
        vnetNames = (pveNodeGet('/cluster/sdn/vnets') ?: []).findAll { it.vnet }.collect { it.vnet.toString() }
    } catch (IOException failure) {
        // An answer that is an error means no SDN here; no answer at all fails the run.
        if (pveFailureStatus(failure) == null) { throw failure }
    }
    def rows = pveNodeGet('/nodes/' + nodeName + '/network')
    def bridgeNames = pveNodeBridgeNames(rows, vnetNames)
    pveNetBridges(rows).findAll { it.iface.toString() in bridgeNames }.each { bridge ->
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
            cluster ? pveEri(pveBridgeKey(cluster, nodeName, name), 'VirtualSwitch') : [:]
        )
    }
    pveNodeVlans(nodeName, bridgeNames, vnetNames).each { vlan ->
        pveDiscover(
            vlan.bridge + '-vlan' + vlan.tag,
            vlan.bridge + ' VLAN ' + vlan.tag + ' (vlan)',
            'VLAN ' + vlan.tag + ' on bridge ' + vlan.bridge + ' on ' + nodeName + ', used by guest NICs',
            [
                pve_node      : nodeName,
                pve_iface     : vlan.bridge,
                pve_iface_type: 'vlan',
                pve_vlan      : vlan.tag
            ],
            cluster ? pveEri(pveVlanKey(cluster, nodeName, vlan.bridge, vlan.tag), 'VirtualSwitch') : [:]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox bridge discovery failed: ' + exception.message)
    return 2
}
