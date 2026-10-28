/*
 * Active Discovery: SDN zones and VNets, cluster-wide.
 *
 * A cluster without SDN discovers nothing and exits 0. Wildvalues are "zone/<zone>" and
 * "vnet/<vnet>": Proxmox keeps both names unique in a cluster. Each instance carries the
 * vertex Proxmox_VE_Cluster_Topology draws between the nodes and the guests on that network,
 * type VirtualSwitch.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def sdn = pveSdn()
    if (sdn == null) { return 0 }
    def cluster = pveCarrierCluster('SDN', false)
    sdn.zones.each { zone ->
        def name = zone.zone.toString()
        pveDiscover(
            'zone/' + name,
            name + ' (SDN zone' + (zone.type ? ', ' + zone.type : '') + ')',
            'SDN zone ' + name + (zone.bridge ? ' on bridge ' + zone.bridge : ''),
            [pve_sdn_kind: 'zone', pve_zone: name, pve_zone_type: zone.type, pve_bridge: zone.bridge],
            cluster ? pveEri(pveSdnKey(cluster, name, null), 'VirtualSwitch') : [:]
        )
    }
    sdn.vnets.each { vnet ->
        def name = vnet.vnet.toString()
        pveDiscover(
            'vnet/' + name,
            name + ' (VNet in ' + vnet.zone + (vnet.tag != null ? ', tag ' + vnet.tag : '') + ')',
            'SDN VNet ' + name + (vnet.alias ? ', ' + vnet.alias : ''),
            [pve_sdn_kind: 'vnet', pve_zone: vnet.zone, pve_vnet: name, pve_tag: vnet.tag],
            cluster ? pveEri(pveSdnKey(cluster, vnet.zone.toString(), name), 'VirtualSwitch') : [:]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox SDN discovery failed: ' + exception.message)
    return 2
}
