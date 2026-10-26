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

// key=value pairs of a Proxmox net0 / ipconfig0 string.
def pveNsPairs = { String value ->
    (value ?: '').tokenize(',').collect { it.split('=', 2) }.findAll { it.size() == 2 }
        .collectEntries { [(it[0].trim()): it[1].trim()] }
}
def PVE_NS_MAC = /(?i)^([0-9a-f]{2}:){5}[0-9a-f]{2}$/

// The MAC of a guest's first NIC, lower case, from its config: lxc's hwaddr=, qemu's
// <model>=<mac>.
def pveNsNet0Mac = { Map config ->
    pveNsPairs(config?.net0?.toString()).values().find { it ==~ PVE_NS_MAC }?.toLowerCase()
}

/*
 * A guest's address for its hostname (N§5.3), or null:
 * 1. live -- the container's interfaces list, or the guest agent's network-get-interfaces
 *    answer -- on the interface whose MAC is net0's: its first IPv4, else its first IPv6
 *    that is not link-local. Never the first address the guest reports: guests list Docker
 *    bridges, VPN tunnels, loopback and link-local beside the real one, often first;
 * 2. the static address in the config: a container's ip= on net0, a VM's cloud-init
 *    ipconfig0, unless dhcp or manual.
 */
def pveGuestAddress = { Map guest, Map config, Object live ->
    def mac = pveNsNet0Mac(config)
    def bare = { String ip -> ip?.replaceAll(/\/\d+$/, '')?.trim() }
    if (mac && live != null) {
        def interfaces = live instanceof Map ? (live.result ?: []) : (live ?: [])
        def own = interfaces.find { (it.hwaddr ?: it.get('hardware-address'))?.toString()?.toLowerCase() == mac }
        def ips = own == null ? [] : own.containsKey('ip-addresses') ?
            own.get('ip-addresses').collect { it.get('ip-address')?.toString() } :
            [own.inet, own.inet6].findAll { it }.collect { it.toString() }
        ips = ips.collect { bare(it) }.findAll { it }
        def ip = ips.find { it.contains('.') } ?: ips.find { it.contains(':') && !it.toLowerCase().startsWith('fe80') }
        if (ip) { return [ip: ip, source: 'live'] }
    }
    def fixed = guest?.type?.toString() == 'lxc' ? pveNsPairs(config?.net0?.toString()).ip :
                                                   pveNsPairs(config?.ipconfig0?.toString()).ip
    if (fixed && !(fixed in ['dhcp', 'manual'])) { return [ip: bare(fixed), source: 'config'] }
    return null
}

/*
 * Whether the NetScan properties select a guest: every filter that is set must pass, and a
 * template never does. Filters are comma-separated allow-lists; vmid takes ranges.
 */
def pveGuestSelected = { Map guest, Map props ->
    if (guest.template?.toString() in ['1', 'true']) { return false }
    def list = { String key -> (props.get(key)?.toString() ?: '').tokenize(',')*.trim().findAll { it } }
    def vmid = guest.vmid as int
    def vmids = list('pve.netscan.guests.filter.vmid')
    def tags = (guest.tags?.toString() ?: '').tokenize(';, ')
    def pass = { String key, Object value -> def allowed = list(key); !allowed || value?.toString() in allowed }
    return pass('pve.netscan.guests.filter.pool', guest.pool) &&
           pass('pve.netscan.guests.filter.node', guest.node) &&
           pass('pve.netscan.guests.filter.type', guest.type) &&
           pass('pve.netscan.guests.filter.status', guest.status) &&
           (!list('pve.netscan.guests.filter.tag') || tags.any { it in list('pve.netscan.guests.filter.tag') }) &&
           (!vmids || vmids.any { range ->
               def ends = range.tokenize('-')
               ends.size() == 2 ? vmid >= (ends[0] as int) && vmid <= (ends[1] as int) : vmid == (range as int)
           })
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

    /*
     * Guests (N§5.3), only when asked for, and only with LogicMonitor API credentials: without
     * them the run cannot see existing resources, so every guest already monitored, and every
     * guest whose address changes, would become a second resource.
     */
    if (prop('pve.netscan.guests', 'false') == 'true') {
        if (input.existing == null) {
            report << 'guests not created: no LogicMonitor API credentials, so existing resources cannot be checked'
        } else {
            def byGuestName = prop('pve.netscan.guest.hostname', 'ip') == 'name'
            def unaddressed = prop('pve.netscan.guests.unaddressed', 'false') == 'true'
            def selected = (input.resources ?: []).findAll { it.type in ['qemu', 'lxc'] && it.vmid != null }
                                                 .findAll { pveGuestSelected(it, props) }
                                                 .sort { it.vmid as int }
            def created = []
            selected.each { guest ->
                def vmid = guest.vmid as int
                def found = (input.addresses ?: [:])[vmid]
                if (!found && !byGuestName && !unaddressed) {
                    report << ('guest ' + vmid + ' ' + guest.name + ': no address, not created')
                    return
                }
                created << [guest: guest, hostname: byGuestName || !found ? guest.name?.toString() : found.ip]
            }
            def sharedNames = created.countBy { it.guest.name?.toString() }.findAll { it.value > 1 }.keySet()
            def guestGroup = standalone ? root + '/Host - ' + cluster + '/Guests' : root + '/Cluster - ' + cluster + '/Guests'
            created.each { entry ->
                def guest = entry.guest
                def name = guest.name?.toString()
                resources << [hostname: entry.hostname,
                              displayname: name in sharedNames ? name + ' (' + guest.vmid + ')' : name,
                              hostProps: [
                                  'system.categories' : 'ProxmoxVE_Guest',
                                  'pve.cluster'       : cluster,
                                  'pve.node'          : guest.node?.toString(),
                                  'pve.vmid'          : guest.vmid.toString(),
                                  'pve.guest.type'    : guest.type.toString(),
                                  'pve.pool'          : guest.pool?.toString() ?: 'none',
                                  'pve.tags'          : guest.tags?.toString() ?: 'none',
                                  'pve.guest.mac'     : pveNsNet0Mac((input.configs ?: [:])[guest.vmid as int]) ?: 'none',
                                  'netscan.powerstate': guest.status?.toString() ?: 'unknown',
                                  'pve.serial'        : cluster + '::' + guest.vmid,
                                  'pve.netscan.status': 'ok'],
                              groupName: [guestGroup]]
            }
        }
    }
    return [resources: resources, report: report]
}
