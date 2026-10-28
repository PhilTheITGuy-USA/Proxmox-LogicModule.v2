/*
 * BatchScript collection: SDN zone state on each node, from /cluster/resources. A VNet takes
 * its zone's state: Proxmox reports state per zone and node, not per VNet.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}
// Only the resource that holds the cluster role now collects; the other prints nothing.
if (!pveRole('Proxmox_VE_Cluster_SDN').collect) {
    return 0
}

try {
    def sdn = pveSdn()
    if (sdn == null) { return 0 }
    def rows = (pveGet('/cluster/resources?type=sdn') ?: []).findAll { it.sdn }
    def emitState = { String id, String zone ->
        def zoneRows = rows.findAll { it.sdn.toString() == zone }
        def ok = zoneRows.count { it.status?.toString() in ['ok', 'available'] }
        pveEmit(id, 'NodesTotal', zoneRows.size())
        pveEmit(id, 'NodesOK', ok)
        // No rows: the zone is defined but not applied yet, which says nothing about its health.
        if (zoneRows) {
            pveEmit(id, 'Healthy', ok == zoneRows.size() ? 1 : 0)
        }
    }
    sdn.zones.each { zone -> emitState('zone/' + zone.zone, zone.zone.toString()) }
    sdn.vnets.each { vnet -> emitState('vnet/' + vnet.vnet, vnet.zone.toString()) }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox SDN collection failed: ' + exception.message)
    return 2
}
