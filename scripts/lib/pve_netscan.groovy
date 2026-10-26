/*
 * The NetScan's planning logic, prepended (after pve_common.groovy) to the NetScan body only.
 *
 * pveNetscanPlan is pure: it turns what the NetScan read from Proxmox and from the portal
 * into the resources it emits, so the harness can test every decision here without a
 * Collector. The body (scripts/Proxmox_VE_NetScan.groovy) makes the reads and calls the
 * snippets.
 *
 * A NetScan re-run overwrites a property's value but never removes a property, a category
 * or a group it stopped sending. So every property is sent on every run with an explicit
 * value (ok, none, true/false), categories mark only what a resource is, and only groups
 * whose membership never changes are written.
 */

// The host part of a URL or of a bare address, without brackets, lower case.
def pveNsHost = { String value ->
    if (!value) { return null }
    def text = value.trim()
    def host = text.contains('://') ? new URI(text).host : text
    host?.replaceAll(/^\[|\]$/, '')?.toLowerCase()
}

/*
 * input:
 *   status    /cluster/status rows
 *   resources /cluster/resources rows (guests: Task 13)
 *   reach     node name -> [api: boolean, ping: boolean, why: String]
 *   configs   vmid -> guest config (Task 13)
 *   addresses vmid -> [ip: String, source: String] (Task 13)
 *   props     the NetScan properties
 *   existing  the portal's devices, or null without LogicMonitor API credentials (Task 14)
 * returns [resources: [[hostname, displayname, hostProps, groupName]], report: [String]]
 */
def pveNetscanPlan = { Map input ->
    def props = input.props ?: [:]
    def prop = { String key, String fallback = null ->
        def value = props.get(key)
        (value == null || value.toString().trim().isEmpty()) ? fallback : value.toString().trim()
    }
    def report = []
    def status = input.status ?: []
    def reach = input.reach ?: [:]
    def clusterRow = status.find { it.type?.toString() == 'cluster' }
    def nodes = status.findAll { it.type?.toString() == 'node' && it.name }.sort { (it.nodeid ?: 0) as int }
    def standalone = clusterRow == null
    def cluster = standalone ? nodes[0]?.name?.toString() : clusterRow.name?.toString()
    def root = prop('pve.netscan.rootFolder', 'Proxmox VE')
    def apiUrl = prop('pve.api.url')
    def apiHosts = nodes.collect { it.ip?.toString() }.findAll { it }.join(',')

    // The cluster's own address: set explicitly, or the API address when it is no node's.
    def address = pveNsHost(prop('pve.netscan.cluster.address') ?: apiUrl)
    def nodeAddresses = nodes.collectMany { [it.name?.toString()?.toLowerCase(), pveNsHost(it.ip?.toString())] }.findAll { it } as Set
    def ownAddress = !standalone && address && !(address in nodeAddresses) ? address : null

    // Every resource gets the token set, with explicit defaults.
    def tokenSet = [
        'pve.token.credential': prop('pve.token.credential', ''),
        'pve.api.insecure'    : prop('pve.api.insecure', 'false'),
        'pve.api.timeout'     : prop('pve.api.timeout', '10000'),
        'pve.api.concurrency' : prop('pve.api.concurrency', '2'),
        'pve.api.url'         : apiUrl ?: '',
        'pve.api.hosts'       : apiHosts,
        'pve.cluster'         : cluster ?: '',
    ]

    // The role pair: the two lowest node ids, whether online or not, so every run picks the
    // same pair and the role never moves on its own (N§5.1).
    def pair = nodes.take(2)*.name*.toString()
    def roleOf = [:]
    def activeOf = [:]
    nodes.each { roleOf[it.name.toString()] = 'none'; activeOf[it.name.toString()] = 'none' }
    if (ownAddress) {
        if (pair) { roleOf[pair[0]] = 'standby'; activeOf[pair[0]] = 'address:' + ownAddress }
        report << ('cluster address ' + ownAddress + ': separate cluster resource' +
                   (pair ? ', standby ' + pair[0] : ', no standby'))
    } else if (pair) {
        roleOf[pair[0]] = 'active'
        if (pair.size() > 1) { roleOf[pair[1]] = 'standby'; activeOf[pair[1]] = pair[0] }
        report << ((standalone ? 'standalone host: ' : 'no cluster address: ') + 'active ' + pair[0] +
                   (pair.size() > 1 ? ', standby ' + pair[1] : ', no standby'))
    }

    def resources = []
    if (ownAddress) {
        resources << [hostname: ownAddress, displayname: cluster,
                      hostProps: tokenSet + ['system.categories': 'ProxmoxVE_Cluster', 'pve.serial': cluster,
                                             'pve.cluster.role': 'active', 'pve.cluster.active': 'none',
                                             'pve.netscan.status': 'ok'],
                      groupName: [root + '/Cluster - ' + cluster]]
    }

    def byName = prop('pve.netscan.node.hostname', 'ip') == 'name'
    def keepAll = prop('pve.netscan.nodes', 'true') != 'false'
    nodes.each { row ->
        def name = row.name.toString()
        if (!keepAll && roleOf[name] == 'none') { return }
        def checked = reach[name]
        def api = checked?.api == true
        def why = !checked ? 'not checked' : !api ? (checked.why ?: 'API did not answer') :
                  checked.ping == false ? 'no ping' : 'ok'
        resources << [hostname: byName ? name : (row.ip?.toString() ?: name), displayname: name,
                      hostProps: tokenSet + [
                          'system.categories'    : standalone ? 'ProxmoxVE_Cluster,ProxmoxVE_Node' : 'ProxmoxVE_Node',
                          'pve.node'             : name,
                          'pve.serial'           : cluster + '::node::' + name,
                          'pve.cluster.role'     : roleOf[name],
                          'pve.cluster.active'   : activeOf[name],
                          'pve.netscan.reachable': api ? 'true' : 'false',
                          'pve.netscan.status'   : why],
                      groupName: [standalone ? root + '/Host - ' + name : root + '/Cluster - ' + cluster + '/Nodes']]
    }
    return [resources: resources, report: report]
}
