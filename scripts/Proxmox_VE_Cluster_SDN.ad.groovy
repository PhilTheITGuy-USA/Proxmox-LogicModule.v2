/*
 * Active Discovery: SDN zones and VNets, cluster-wide.
 *
 * A cluster without SDN zones discovers nothing and exits 0. Wildvalues are "zone/<zone>" and
 * "vnet/<vnet>": Proxmox keeps both names unique in a cluster. Each instance carries the
 * vertex Proxmox_VE_Cluster_Topology draws between the nodes and the guests on that network,
 * type VirtualSwitch.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    /*
     * The module auto-deletes (spec A§3.2), so a run that read nothing must fail rather than
     * answer "no SDN": an error answer (pveSdn's null; SDN is part of Proxmox VE 8 and 9),
     * or empty lists the token may not see, which Proxmox filters by SDN.Audit per zone.
     */
    def sdn = pveSdn()
    if (sdn == null) {
        System.err.println('Proxmox SDN discovery stopped, so no SDN instance is retired: the SDN configuration was not readable.')
        return 2
    }
    if (!sdn.zones && !sdn.vnets && pveListHidden(pveGet, '/sdn/zones', 'SDN.Audit')) {
        System.err.println(pveHiddenReason('The SDN zones and VNets', '/sdn/zones', 'SDN.Audit'))
        return 2
    }
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
