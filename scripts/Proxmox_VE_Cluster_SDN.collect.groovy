/*
 * BatchScript collection: SDN zone state on each node, from /cluster/resources. A VNet takes
 * its zone's state: Proxmox reports state per zone and node, not per VNet.
 *
 * Proxmox 8 lists a zone as type "sdn", its name in "sdn". Proxmox 9 lists it as type
 * "network" with network-type "zone", its name in "network", and answers ?type=sdn with an
 * empty list (lab, PVE 9.2.3) -- so the list is read unfiltered and both shapes are taken.
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
    def rows = (pveGet('/cluster/resources') ?: []).collect { row ->
        if (row.type?.toString() == 'sdn' && row.sdn) { return [zone: row.sdn.toString(), status: row.status] }
        if (row.type?.toString() == 'network' && row.'network-type'?.toString() == 'zone' && row.network) {
            return [zone: row.network.toString(), status: row.status]
        }
        null
    }.findAll()
    def emitState = { String id, String zone, boolean alerts ->
        def zoneRows = rows.findAll { it.zone == zone }
        def ok = zoneRows.count { it.status?.toString() in ['ok', 'available'] }
        pveEmit(id, 'NodesTotal', zoneRows.size())
        pveEmit(id, 'NodesOK', ok)
        // No rows: the zone is defined but not applied yet, which says nothing about its health.
        if (zoneRows && alerts) {
            pveEmit(id, 'Healthy', ok == zoneRows.size() ? 1 : 0)
        }
    }
    // Only the zone reports Healthy, the datapoint that alerts: a VNet's figures are its zone's,
    // so a zone failing on a node raises one alert, not one more per VNet in it (user, 2026-10-31).
    sdn.zones.each { zone -> emitState('zone/' + zone.zone, zone.zone.toString(), true) }
    sdn.vnets.each { vnet -> emitState('vnet/' + vnet.vnet, vnet.zone.toString(), false) }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox SDN collection failed: ' + exception.message)
    return 2
}
