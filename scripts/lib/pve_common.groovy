//!/lib-groovy/v4
/*
 * Shared preamble for the Proxmox VE LogicModule suite.
 *
 * This file is NOT a standalone script. build/build.py prepends it to every module
 * body, so the assembled script that ships inside the module JSON is self-contained.
 * Edit helpers here once rather than in each body.
 *
 * Properties are read from netscanProps when the script runs as a NetScan, else from
 * hostProps, so the same helpers serve modules and the NetScan alike.
 *
 * Resource properties consumed:
 *   pve.api.url       Base URL, e.g. https://pve.example.com:8006
 *                     Defaults to https://<system.hostname>:<pve.api.port> when unset.
 *   pve.api.port      Default 8006. Only used to build the fallback URL.
 *   pve.token.credential  Full token string: user@realm!tokenid=secret
 *   pve.api.timeout   Connect and read timeout in ms. Default 10000.
 *   pve.api.insecure  "true" to accept self-signed certificates. Lab use only.
 */
import groovy.json.JsonSlurper
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

// ---------------------------------------------------------------- properties

def pveProps = binding.hasVariable('netscanProps') ? netscanProps
             : binding.hasVariable('hostProps') ? hostProps : [:]

def pveHostProp = { String key, String fallback = null ->
    def value = pveProps.get(key)
    (value == null || value.toString().trim().isEmpty()) ? fallback : value.toString().trim()
}

/*
 * Instance properties are exposed as taskProps during collection and instanceProps
 * in some contexts, with or without the "auto." prefix that Active Discovery adds.
 * Neither binding exists during Active Discovery itself, hence the hasVariable guards.
 */
def pveInstanceProp = { String key ->
    def sources = []
    if (binding.hasVariable('taskProps') && taskProps != null) { sources << taskProps }
    if (binding.hasVariable('instanceProps') && instanceProps != null) { sources << instanceProps }
    for (source in sources) {
        for (candidate in [key, 'auto.' + key]) {
            def value = source.get(candidate)
            if (value != null && !value.toString().trim().isEmpty()) {
                return value.toString().trim()
            }
        }
    }
    return null
}

// ---------------------------------------------------------------- connection

def pveBaseUrl = pveHostProp('pve.api.url')
if (!pveBaseUrl) {
    def hostname = pveHostProp('system.hostname')
    if (hostname) {
        pveBaseUrl = 'https://' + hostname + ':' + pveHostProp('pve.api.port', '8006')
    }
}
def pveToken = pveHostProp('pve.token.credential')
def pveTimeout = pveHostProp('pve.api.timeout', '10000') as int

def pveConfigError = null
if (!pveBaseUrl) {
    pveConfigError = 'Cannot determine the Proxmox API URL: set pve.api.url, or ensure system.hostname resolves.'
} else if (!pveToken) {
    pveConfigError = 'Missing pve.token.credential. Expected the full token string: user@realm!tokenid=secret'
}

if (pveHostProp('pve.api.insecure', 'false').toBoolean()) {
    def trustAll = [
        getAcceptedIssuers: { -> new X509Certificate[0] },
        checkClientTrusted: { X509Certificate[] certs, String authType -> },
        checkServerTrusted: { X509Certificate[] certs, String authType -> }
    ] as X509TrustManager
    def sslContext = SSLContext.getInstance('TLS')
    sslContext.init(null, [trustAll] as TrustManager[], new java.security.SecureRandom())
    HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory())
    HttpsURLConnection.setDefaultHostnameVerifier({ String hostname, SSLSession session -> true } as HostnameVerifier)
}

/*
 * A base URL for one address: the scheme and port of pve.api.url (https and pve.api.port,
 * default 8006, without it). An address that carries its own port keeps it; a bare IPv6
 * address is bracketed, as a URL requires; one written as a URL is used as written, less any
 * trailing /api2/json, which pveGetAt adds.
 */
def pveUrlScheme = { -> pveBaseUrl ? (new URI(pveBaseUrl).scheme ?: 'https') : 'https' }
def pveUrlPort = { ->
    def port = pveBaseUrl ? new URI(pveBaseUrl).port : -1
    port > 0 ? port : (pveHostProp('pve.api.port', '8006') as int)
}
def pveHostUrl = { String host ->
    def address = host?.trim()
    if (!address) { return null }
    if (address.contains('://')) { return address.replaceAll('(/api2/json)?/*$', '') }
    def prefix = pveUrlScheme() + '://'
    if (address.startsWith('[')) { return prefix + (address.contains(']:') ? address : address + ':' + pveUrlPort()) }
    if (address.count(':') > 1) { return prefix + '[' + address + ']:' + pveUrlPort() }
    if (address.contains(':')) { return prefix + address }
    return prefix + address + ':' + pveUrlPort()
}

/*
 * Every address the cluster API can be asked at, in order: on a node resource its own API
 * first, then pve.api.url, then pve.api.hosts. A node answers for the cluster as it sees it,
 * and the role check has just vouched for that view; pve.api.url may be the very node a
 * standby is standing in for, whose view is stale.
 */
def pveApiBases = { ->
    def own = pveHostProp('pve.node') ? pveHostUrl(pveHostProp('system.hostname')) : null
    ([own, pveBaseUrl?.replaceAll('/+$', '')] +
        pveHostProp('pve.api.hosts', '').tokenize(',').collect { pveHostUrl(it) })
        .findAll { it }.unique()
}

/*
 * GET an /api2/json path at one base URL and return the unwrapped "data" payload.
 *
 * 401/403 is called out separately because Proxmox returns it when the token exists
 * but lacks an ACL entry, which is the single most common misconfiguration and is
 * otherwise indistinguishable from a genuinely empty result.
 */
def pveGetAt = { String base, String path ->
    def connection = new URL(base.replaceAll('/+$', '') + '/api2/json' + path).openConnection()
    connection.setRequestMethod('GET')
    connection.setConnectTimeout(pveTimeout)
    connection.setReadTimeout(pveTimeout)
    connection.setRequestProperty('Authorization', 'PVEAPIToken=' + pveToken)
    connection.setRequestProperty('Accept', 'application/json')

    int status = connection.responseCode
    if (status == 401 || status == 403) {
        throw new IOException('HTTP ' + status + ' for ' + path +
            ' - the API token in pve.token.credential was rejected. Check its secret, and that it has a' +
            ' PVEAuditor ACL. A token with privilege separation enabled needs its own ACL entry,' +
            ' not just the one on its user.')
    }
    if (status < 200 || status >= 300) {
        throw new IOException('HTTP ' + status + ' for ' + path)
    }
    def payload = new JsonSlurper().parse(connection.inputStream)
    connection.disconnect()
    return payload?.data
}

// The HTTP status a pveGetAt failure carries, or null for a connection failure or timeout.
def pveFailureStatus = { Exception failure ->
    def match = failure.message =~ /^HTTP (\d+) /
    match.find() ? (match.group(1) as int) : null
}

/*
 * GET from the cluster API, failing over through pveApiBases: every node answers for the
 * whole cluster, so a node that is down costs one timeout, not the poll. The address that
 * answered is asked first for the rest of the run. A rejected token (401/403) or another
 * client error is not failed over: it would be the same at every address.
 */
def pveActiveBase = null
// The addresses to try for a path. A /nodes/<name> path names one node, which every address
// only proxies to, so a second address is tried at most: the rest would time out the same way.
def pveBasesFor = { String path ->
    def bases = pveApiBases()
    if (pveActiveBase in bases) { bases = [pveActiveBase] + (bases - pveActiveBase) }
    path.startsWith('/nodes/') ? bases.take(2) : bases
}
def pveGet = { String path ->
    def bases = pveBasesFor(path)
    Exception last = null
    for (base in bases) {
        try {
            def data = pveGetAt(base, path)
            pveActiveBase = base
            return data
        } catch (IOException failure) {
            def status = pveFailureStatus(failure)
            if (status != null && status < 500) { throw failure }
            last = failure
        }
    }
    throw last ?: new IOException('No Proxmox API address to ask: set pve.api.url')
}

// A node resource's own API. Node modules use only this, and never fail over: another
// node would answer for itself, not for this one.
def pveSelfBase = pveHostUrl(pveHostProp('system.hostname'))
def pveNodeGet = { String path ->
    if (!pveSelfBase) { throw new IOException('Cannot reach this node: system.hostname is empty') }
    pveGetAt(pveSelfBase, path)
}

// The node this resource is: pve.node, which the NetScan writes, else the row /cluster/status
// marks local when this node's own API is asked.
def pveSelfNode = { ->
    def named = pveHostProp('pve.node')
    if (named) { return named }
    def local = (pveNodeGet('/cluster/status') ?: []).find { it.type?.toString() == 'node' && it.local }
    if (local?.name) { return local.name.toString() }
    throw new IOException('Cannot tell which node this resource is: set pve.node')
}

// -------------------------------------------------------------------- output

/*
 * Instance IDs (wildvalues) are derived from Proxmox's own resource id -- "qemu/101",
 * "node/pve1", "storage/pve1/local". Those are cluster-unique and, for guests, survive
 * migration between nodes, so an HA failover does not orphan the instance.
 *
 * "/" and "." are folded to "-": "." separates instance from datapoint in BatchScript
 * output, and a storage name may legally contain one.
 */
def pveWildValue = { value ->
    (value == null ? '' : value.toString()).replaceAll('[^A-Za-z0-9_-]', '-')
}

def pveDisplayName = { value ->
    (value == null ? '' : value.toString()).replaceAll('#', ' ').trim()
}

// LogicMonitor's reference discovery scripts URL-encode property values rather than
// stripping characters, which keeps names, tags and pools readable and reversible.
def pvePropValue = { value ->
    URLEncoder.encode(value == null ? '' : value.toString(), 'UTF-8')
}

// instanceId##displayName##description####auto.key=value&auto.key2=value2
//
// rawProperties are emitted after the auto. pairs with their keys untouched. That is the
// only way to set a predef.* property such as predef.externalResourceID, which the
// portal reads under exactly that name and would never find as auto.predef.*.
def pveDiscover = { String id, String name, String description, Map properties,
                    Map rawProperties = [:] ->
    def pairs = properties
        .findAll { key, value -> value != null && !value.toString().trim().isEmpty() }
        .collect { key, value -> 'auto.' + key + '=' + pvePropValue(value) }
    pairs += rawProperties
        .findAll { key, value -> value != null && !value.toString().trim().isEmpty() }
        .collect { key, value -> key + '=' + pvePropValue(value) }
    println(pveWildValue(id) + '##' + pveDisplayName(name ?: id) + '##' +
            pveDisplayName(description) + '####' + pairs.join('&'))
}

// BatchScript emits "instanceId.datapoint=value"; plain Script omits the instance.
def pveEmit = { String instanceId, String key, Object value ->
    println((instanceId ? pveWildValue(instanceId) + '.' : '') + key + '=' + value)
}

def pveRound = { double value ->
    Math.round(value * 100.0d) / 100.0d
}

/*
 * Bytes are what the API reports and what a datapoint should hold for arithmetic, but
 * they are not what a dashboard can print: LogicMonitor renders 500107862016 as 5.001E11.
 * Capacity figures are therefore emitted in GB and rates in MB, decimal in both cases
 * (10^9 / 10^6) to match how drive and link capacities are labelled.
 */
def pveGB = { value ->
    pveRound(((value ?: 0) as double) / 1000000000.0d)
}

def pveMB = { value ->
    pveRound(((value ?: 0) as double) / 1000000.0d)
}

/*
 * Joins the parts of a topology key, folded to the alphabet an ERI key can carry. Both
 * node keys and the cluster key go through it, so the two sides that build each key
 * cannot disagree on case or punctuation.
 */
def pveTopoFold = { List parts ->
    parts.collect { it.toString().toLowerCase().replaceAll('[^a-z0-9_-]', '-') }.join('--')
}

/*
 * The synthesised topology key for a Proxmox vertex, used for the cluster and for every
 * node. Proxmox exposes no hardware identifier for a node anywhere in its API, so the node
 * vertex cannot be matched on something it already has the way a guest is matched on its
 * MAC. addERI_Proxmox_VE_Node stamps the identical key on the node's own resource, and this is
 * the only place its shape is defined -- change it here and there together or the two stop
 * matching, which shows up as a map full of vertices that resolve to nothing.
 */
def pveTopoKey = { String cluster, String node ->
    def parts = node ? ['proxmoxve', cluster, node] : ['proxmoxve', cluster]
    pveTopoFold(parts)
}

/*
 * Which node, and for pveTopoNodeIdentity which cluster, a resource stands for, read from
 * its properties alone -- for a node resource that carries no API token, and so cannot
 * ask /cluster/status.
 *
 * The node name is pve.topology.node when set to a name, and otherwise the short form of
 * system.sysname or system.hostname: Proxmox requires a node's name to be its hostname,
 * so the short hostname is the node name. An IP address says nothing about the name and
 * is skipped, in the property too. The cluster name, which only the older cluster-scoped
 * key needs, has no source but the optional pve.topology.cluster. Returns null rather than guessing: a wrong key matches no vertex,
 * which is indistinguishable on the map from no key at all, but costs a stray ERI.
 */
def pveTopoNodeName = { ->
    // An address is never a node name: pvecm status lists members by corosync address, and
    // an address copied into pve.topology.node would key the node as "192".
    def isName = { String value -> value && !(value.trim() ==~ /\d{1,3}(\.\d{1,3}){3}/) && !value.contains(':') }
    // pve.node is what the NetScan writes; pve.topology.node is the hand-set fallback.
    def node = pveHostProp('pve.node')
    if (!isName(node)) { node = pveHostProp('pve.topology.node') }
    if (!isName(node)) {
        def hostname = ['system.sysname', 'system.hostname']
            .collect { pveHostProp(it) }
            .find { isName(it) }
        node = hostname?.tokenize('.')?.getAt(0)
    }
    return node ?: null
}

// The cluster and node together, for pveTopoKey's node key; null without pve.topology.cluster.
def pveTopoNodeIdentity = { ->
    def cluster = pveHostProp('pve.topology.cluster')
    if (!cluster) { return null }
    def node = pveTopoNodeName()
    return node ? [cluster: cluster, node: node] : null
}

/*
 * One address in the form both sides of the node IP key agree on, or null.
 *
 * The TopologySource reads a node's address from /cluster/status, which may write IPv6
 * compressed (fd00::12); addERI_Proxmox_VE_Node reads system.ips, where LogicMonitor writes it
 * in full (fd00:0:0:0:0:0:0:12). InetAddress settles both to the full form. It is handed
 * only a literal: given anything else Java resolves it as a hostname, and a PropertySource
 * that runs on every opted-in resource must not make DNS lookups. A dotted quad is checked
 * octet by octet for that reason, and an IPv6 candidate goes in bracketed, which makes Java
 * refuse rather than resolve one it cannot parse. Loopback, link-local and the wildcard
 * name no reachable node, and every host has the first two, so they are dropped.
 */
def pveTopoIp = { String raw ->
    def text = raw?.trim()?.replaceFirst(/%.*$/, '')
    if (!text) { return null }
    def quad = text ==~ /\d{1,3}(\.\d{1,3}){3}/ && text.tokenize('.').every { (it as int) <= 255 }
    if (!quad && !text.contains(':')) { return null }
    try {
        def address = InetAddress.getByName(quad ? text : '[' + text + ']')
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
            return null
        }
        return address.getHostAddress().toLowerCase()
    } catch (Exception ignored) {
        return null
    }
}

/*
 * The node key that needs no cluster name: the node's short name and one of its
 * addresses. A token-less node cannot learn its cluster's name, but it knows its own name
 * and addresses, and /cluster/status gives the TopologySource the same two for every node.
 * The name alone collides across clusters (pve1 is everyone's first node) and the address
 * alone across overlapping private networks; together they are unique in practice, and
 * topo.namespace covers the environment that reuses both. Null when either part is
 * missing or the address is not one pveTopoIp accepts.
 */
def pveTopoNodeKey = { String node, String ip ->
    def name = node?.trim()?.tokenize('.')?.getAt(0)
    def address = pveTopoIp(ip)
    return (name && address) ? pveTopoFold(['proxmoxve', 'node', name, address]) : null
}

// pveTopoNodeKey over every address a node holds: unusable ones dropped, duplicates once.
def pveTopoNodeKeys = { String node, List ips ->
    (ips ?: []).collect { pveTopoNodeKey(node, it?.toString()) }.findAll { it }.unique()
}

/*
 * The one key a node vertex carries. Not both: lm.topo joins a vertex's keys into a single
 * ID, and the portal then matches only a resource carrying every one of them, so a
 * token-less node stamped with the IP key alone would go unmatched. The IP key whenever
 * /cluster/status gives a usable address, since every node's resource carries it; the
 * cluster-scoped key otherwise.
 */
def pveTopoNodeVertexKey = { String cluster, String node, String ip ->
    pveTopoNodeKey(node, ip) ?: pveTopoKey(cluster, node)
}

/*
 * The MAC of a guest's first virtual NIC, out of a guest config.
 *
 * QEMU writes it as "virtio=BC:24:11:0E:00:02,bridge=vmbr0" and LXC as
 * "name=eth0,bridge=vmbr0,hwaddr=BC:24:11:...,type=veth", so the key differs but the shape
 * of the value does not: pull the first thing that looks like a MAC. Lowercased, because
 * that is how LogicMonitor stores an ERI key and the two have to compare equal.
 */
def pveGuestMac = { config ->
    if (!config) { return null }
    def interfaces = config.keySet().findAll { it.toString() ==~ /net\d+/ }.sort()
    for (name in interfaces) {
        def matcher = (config[name].toString() =~ /([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})/)
        if (matcher.find()) { return matcher.group(1).toLowerCase() }
    }
    return null
}

/*
 * Whether a resource's MAC is one Proxmox itself assigned to a guest. This is what the
 * label PropertySource, addERI_Proxmox_VE_GuestResource, matches with; the topology edges and
 * GuestStatus's instance ERIs read the MAC from the guest config and do not use it.
 * Proxmox generates guest MACs under BC:24:11 by default. A guest with a hand-set MAC (a
 * passed-through or migrated NIC) is somebody else's hardware identity and must not be
 * claimed, and a physical host such as the 88:a2:9e Raspberry Pi on the test portal is
 * the control case that must never match. A site that changed Proxmox's MAC prefix lists
 * its own in prefixProperty (comma separated), and that list REPLACES the default rather
 * than adding to it. The match is a plain string prefix, so give whole octets
 * (02:00:00, not 02:0). Returns the lowercased MAC on a match, else null.
 */
def pveGuestMacMatch = { String mac, String prefixProperty ->
    if (mac == null || mac.trim().isEmpty()) { return null }
    def prefixes = (prefixProperty ?: '').tokenize(',')
        .collect { it.trim().toLowerCase() }
        .findAll { !it.isEmpty() }
    if (prefixes.isEmpty()) { prefixes = ['bc:24:11'] }
    def normalised = mac.trim().toLowerCase()
    return prefixes.any { normalised.startsWith(it) } ? normalised : null
}

/*
 * The MAC a guest resource is keyed by. A guest the NetScan created carries the MAC of its
 * first Proxmox NIC as pve.guest.mac, which proves it is a Proxmox guest whatever its MAC
 * looks like; otherwise the MAC in-guest monitoring reported, if it has Proxmox's prefix
 * (or one set in pve.guest.mac_prefix).
 */
def pveGuestEriMac = { ->
    def own = pveHostProp('pve.guest.mac')
    if (own && own != 'none') { return own.toLowerCase() }
    pveGuestMacMatch(pveHostProp('auto.network.mac_address'), pveHostProp('pve.guest.mac_prefix'))
}

/*
 * The ERI keys a guest resource carries. The bare MAC is the key the TopologySource's guest
 * vertex and GuestStatus's instance are built from; a guest monitored from inside already has
 * it through net.l2, but one the NetScan created is only pinged and has no other, so without
 * it the edge stops at the instance and the resource is drawn alone (lab map, 2026-10-26).
 * proxmoxve--vm--<mac> carries the VirtualMachineResource type at a priority that wins.
 */
def pveGuestEriKeys = { String mac -> [mac, 'proxmoxve--vm--' + mac] }

/*
 * The cluster's own name, which /cluster/status reports as the single row of type
 * "cluster". A standalone host has no such row, so it falls back to the local node's
 * name -- the vertex still has to be unique across a portal that monitors several of
 * them. One definition, because the TopologySource and the guest fallback key must name
 * the same cluster or the two stop matching.
 */
def pveTopoClusterName = { List status ->
    def clusterRow = status.find { it.type?.toString() == 'cluster' }
    def localRow = status.find { it.local }
    return clusterRow?.name?.toString() ?: localRow?.name?.toString() ?: 'standalone'
}

/*
 * The guests discovery and topology both work on: every QEMU VM and LXC container, never
 * a template. Templates are never running, so discovering them creates instances that
 * alert as permanently down.
 *
 * /cluster/resources is filtered by token ACL rather than erroring, so an
 * under-privileged token looks identical to an empty cluster; say so on stderr.
 */
def pveGuestList = { ->
    def resources = pveGet('/cluster/resources?type=vm') ?: []
    if (resources.isEmpty()) {
        System.err.println('/cluster/resources returned no guests. This endpoint is filtered by ' +
            'token ACL rather than erroring, so an under-privileged token looks identical to an ' +
            'empty cluster. Verify the token has VM.Audit on /vms.')
    }
    return resources.findAll { it.type in ['qemu', 'lxc'] && !it.template }
}

/*
 * A size for an instance's name or description, so a percentage graph reads against the
 * size it is a share of: "1.8 TB", "215 GB", "8.6 GB". Decimal units, like pveGB. Empty
 * for a missing or zero size, such as unavailable storage, so nothing misleading is shown.
 */
def pveSizeLabel = { bytes ->
    def value = (bytes ?: 0) as double
    if (value <= 0) { return '' }
    if (value >= 1e12d) { return String.format(Locale.ROOT, '%.1f TB', value / 1e12d) }
    if (value >= 1e10d) { return String.format(Locale.ROOT, '%.0f GB', value / 1e9d) }
    if (value >= 1e9d) { return String.format(Locale.ROOT, '%.1f GB', value / 1e9d) }
    return String.format(Locale.ROOT, '%.0f MB', value / 1e6d)
}

/*
 * One guest's Active Discovery line. The wildvalue comes from Proxmox's own id (qemu/101)
 * and never from the node, so a live migration does not orphan the instance.
 * rawProperties passes straight through to pveDiscover.
 */
def pveGuestDiscover = { Map guest, Map rawProperties = [:] ->
    def vmid = guest.vmid?.toString()
    def kind = guest.type == 'qemu' ? 'virtual machine' : 'container'
    def disk = pveSizeLabel(guest.maxdisk)
    pveDiscover(
        guest.id,
        guest.name ?: (guest.type + '-' + vmid),
        'Proxmox ' + kind + ' ' + vmid + (disk ? ', root disk ' + disk : ''),
        [
            pve_id  : guest.id,
            pve_node: guest.node,
            pve_type: guest.type,
            pve_vmid: vmid,
            pve_pool: guest.pool,
            pve_tags: guest.tags
        ],
        rawProperties
    )
}

def pvePercent = { used, total ->
    def totalValue = (total ?: 0) as double
    totalValue > 0 ? pveRound(((used ?: 0) as double) * 100.0d / totalValue) : 0
}

/*
 * The instances Proxmox_VE_Cluster_Backups discovers and collects: one per guest per backup job,
 * and one per guest no job covers. Discovery and collection both build them here, so the
 * two always agree on the wildvalues.
 *
 * /cluster/backup/{id}/included_volumes applies a job's all / pool / vmid / exclude
 * selection, so each job's guests are exactly the ones it will back up. Proxmox types them
 * "qemu" and "lxc"; its schema documents "VM" and "CT", which are accepted too. Any other
 * type, such as "unknown" for a guest removed but not purged, is skipped. Each guest's
 * volumes come with it, marked included or not, with Proxmox's reason ("not a volume" for a
 * bind mount, "backup=no" for a disk excluded by hand). /cluster/backup-info/not-backed-up
 * is absent on old Proxmox versions; then no uncovered guests are returned and stderr says
 * so, while the job instances are unaffected.
 */
def pveBackupInstances = { ->
    def instances = []
    (pveGet('/cluster/backup') ?: []).each { job ->
        def jobId = job.id?.toString()
        if (!jobId) { return }
        def label = job.comment?.toString()?.trim() ?: jobId
        def included = pveGet('/cluster/backup/' + URLEncoder.encode(jobId, 'UTF-8') + '/included_volumes')
        def guests = (included instanceof Map ? included.children : included) ?: []
        guests.each { guest ->
            def type = [qemu: 'qemu', lxc: 'lxc', VM: 'qemu', CT: 'lxc'][guest.type?.toString()]
            if (!type || guest.id == null) { return }
            def vmid = guest.id.toString()
            def volumes = (guest.children ?: []).findAll { it.id != null }
            def isIncluded = { volume -> volume.included?.toString() in ['1', 'true'] }
            def volumeKey = { volume -> volume.id.toString().tokenize(':').last() }
            instances << [
                id      : jobId + '-' + vmid,
                vmid    : vmid,
                name    : guest.name?.toString(),
                type    : type,
                covered : true,
                jobId   : jobId,
                job     : label,
                schedule: job.schedule?.toString(),
                storage : job.storage?.toString(),
                // Proxmox treats a job without "enabled" as enabled.
                enabled : !(job.enabled?.toString() in ['0', 'false']),
                nextRun : job.'next-run',
                included: volumes.findAll { isIncluded(it) }
                    .collect { volumeKey(it) + ': ' + it.name },
                excluded: volumes.findAll { !isIncluded(it) }
                    .collect { volumeKey(it) + ': ' + it.name + (it.reason ? ' (' + it.reason + ')' : '') }
            ]
        }
    }
    try {
        (pveGet('/cluster/backup-info/not-backed-up') ?: []).each { guest ->
            if (guest.vmid == null) { return }
            def vmid = guest.vmid.toString()
            instances << [id: 'none-' + vmid, vmid: vmid, name: guest.name?.toString(),
                          type: guest.type?.toString(), covered: false, job: 'No backup job']
        }
    } catch (Exception unavailable) {
        System.err.println('Guests covered by no backup job are not listed on this Proxmox ' +
            'version: ' + unavailable.message)
    }
    return instances
}

// ------------------------------------------------------------- node pressure

// The newest row of /nodes/{node}/rrddata that carries data: the last one is often still
// being filled, with every field null.
def pveLatestRrd = { List rows ->
    (rows ?: []).findAll { it?.cpu != null }.with { it ? it[-1] : null }
}

/*
 * How stalled a node is, 0-100: the share of time tasks waited on CPU, IO or memory, from
 * Linux pressure stall information in rrddata. The worst of the three, so one starved
 * resource is not averaged away. Pressure is already relative to the machine's size, and
 * load average already counts tasks waiting on IO, so load and IO wait are not added in.
 * Without pressure fields (a kernel or Proxmox release that does not report them) the
 * 1-minute load per CPU stands in, capped at 100; with neither, null -- never a guess.
 */
def pveNodeStall = { Map rrdRow, Map status ->
    def keys = ['pressurecpusome', 'pressureiosome', 'pressurememorysome']
    def pressure = keys.findAll { rrdRow?.get(it) != null }.collect { rrdRow[it] as double }
    if (pressure) { return pressure.max() }
    def load = status?.loadavg ? status.loadavg[0]?.toString()?.toDouble() : null
    def cpus = status?.cpuinfo?.cpus
    if (load != null && cpus) { return Math.min(100.0d, 100.0d * load / (cpus as double)) }
    return null
}

// Whether a /cluster/status answer is quorate; a standalone host (no cluster row) is.
def pveQuorate = { List status ->
    def clusterRow = status.find { it.type?.toString() == 'cluster' }
    clusterRow == null || clusterRow.quorate?.toString() == '1'
}

/*
 * The cluster health score, 0-100: 100 x (nodes online / nodes) x the mean node score,
 * where an online node scores 1 - stall/100 and an offline node 0, so an offline node
 * counts twice, as it does for its guests. A cluster without quorum scores 0 whatever its
 * nodes say: losing quorum is a cliff, not a slope. An online node with no stall figure is
 * left out of the mean, and with no figure for any node there is no score -- never a
 * fabricated one. A standalone host (no cluster row) counts as quorate.
 */
def pveHealthScore = { List statusRows, Map stallByNode ->
    def nodes = statusRows.findAll { it.type?.toString() == 'node' }
    def isOnline = { row -> row.online == null || row.online?.toString() == '1' }
    def online = nodes.count { isOnline(it) } as double
    def total = nodes.size() as double
    def result = [NodesOnline: online, NodesTotal: total,
                  NodesOnlinePercent: total ? pveRound(100.0d * online / total) : 0.0d]
    if (!pveQuorate(statusRows)) { return result + [HealthScore: 0.0d] }
    def scores = nodes.collect { row ->
        if (!isOnline(row)) { return 0.0d }
        def stall = stallByNode[row.name?.toString()]
        stall == null ? null : 1.0d - (stall as double) / 100.0d
    }
    if (!nodes.any { isOnline(it) && stallByNode[it.name?.toString()] != null }) { return result }
    def counted = scores.findAll { it != null }
    result + [HealthScore: pveRound(100.0d * (online / total) * (counted.sum() / counted.size()))]
}

// --------------------------------------------------------------- cluster role

/*
 * The cluster role sits on an active and a standby resource, named by pve.cluster.role and,
 * on the standby, pve.cluster.active (the active node's name, or address:<host> for a
 * cluster resource). Only one of the pair collects: the other prints nothing, so its
 * datapoints read No Data, which does not alert, and the pair never alerts twice.
 *
 * A node whose own corosync is not running never collects: its API still answers, but with
 * frozen membership and a quorum flag that flips between polls, so its view cannot be
 * trusted. Otherwise a node trusts its own view. With three nodes or more, a node without
 * quorum never collects, so a split cluster has one collecting side. A two-node cluster
 * loses quorum whenever either node is down, so there quorum is not required: the survivor
 * of a lost peer collects. Only a two-node cluster split with both nodes up gets two
 * collectors, which a QDevice (a third vote) prevents. Where its own view cannot be read, an active resource collects
 * anyway, so the failure surfaces as a failed collection; a standby stays quiet.
 */
def pveAddressDown = { String host ->
    def url = new URI(pveHostUrl(host))
    def address = url.host.replaceAll(/^\[|\]$/, '')
    try {
        if (!InetAddress.getByName(address).isReachable(pveTimeout)) { return true }
        new Socket().withCloseable { it.connect(new InetSocketAddress(address, url.port), pveTimeout) }
        return false
    } catch (IOException unreachable) {
        return true
    }
}

// Whether this node's own corosync service is running: true, false, or null when its services
// cannot be read (then the check is skipped rather than guessed).
def pveCorosyncRunning = { ->
    try {
        def service = (pveNodeGet('/nodes/' + pveSelfNode() + '/services') ?: [])
            .find { (it.name ?: it.service)?.toString() == 'corosync' }
        service == null ? null : service.get('active-state')?.toString() == 'active'
    } catch (Exception unreadable) {
        null
    }
}

/*
 * State a module keeps between its runs on one Collector: lm.cache there, a map the harness
 * binds as pveTestState here, nothing where neither exists (then the hand-back below is
 * skipped). Loaded by reflection, so the preamble still runs where the snippet classes do not.
 */
def pveStateStore = null
def pveState = { ->
    if (pveStateStore != null) { return pveStateStore }
    if (binding.hasVariable('pveTestState')) {
        def map = pveTestState
        pveStateStore = [read: { String k -> map[k] }, write: { String k, String v, int ttl -> map[k] = v },
                         drop: { String k -> map.remove(k) }]
        return pveStateStore
    }
    try {
        def snippets = Class.forName('com.logicmonitor.mod.Snippets')
        def helper = Class.forName('com.santaba.agent.groovy.utils.GroovyScriptHelper')
        def loader = helper.getInstance(GroovySystem.version).getScript('Snippets', snippets.getLoader()).withBinding(getBinding())
        def quiet = new PrintStream(new ByteArrayOutputStream())
        def lmDebug = loader.load('lm.debug', '1').debugSnippetFactory(quiet, false, false, 'proxmox-ve')
        def cache = loader.load('lm.cache', '0').cacheSnippetFactory(lmDebug, 'proxmox-ve')
        pveStateStore = [read: { String k -> cache.cacheGet(k) }, write: { String k, String v, int ttl -> cache.cacheSet(k, v, ttl) },
                         drop: { String k -> cache.cacheRemove(k) }]
    } catch (Throwable unavailable) {
        pveStateStore = [read: { String k -> null }, write: { String k, String v, int ttl -> }, drop: { String k -> }]
    }
    return pveStateStore
}

/*
 * The hand-back. Alerts a standby raised while standing in do not clear when it goes quiet --
 * No Data never clears an alert -- so a standby that was collecting collects once more after
 * the resource ahead of it returns: its healthy values clear those alerts. Each module keeps
 * its own record, under its own label, for two hours, renewed on every poll it stands in.
 */
def pveHandBack = { String label, Map result ->
    if (!label) { return result }
    def key = 'handback:' + (pveHostProp('pve.cluster') ?: '') + ':' +
              (pveHostProp('pve.node') ?: pveHostProp('system.hostname') ?: '') + ':' + label
    def state = pveState()
    if (result.collect) {
        state.write(key, 'collecting', 7200)
        return result
    }
    if (state.read(key)) {
        state.drop(key)
        return result + [collect: true, why: result.why + '; hand-back: one more poll to clear its alerts']
    }
    return result
}

def pveRoleResult = null
def pveRole = { String label = null ->
    if (pveRoleResult != null) { return pveRoleResult }
    def role = pveHostProp('pve.cluster.role')
    def active = pveHostProp('pve.cluster.active')
    def ownStatus = { -> pveNodeGet('/cluster/status') ?: [] }
    def result
    try {
        if (role == null) {
            // A standalone host added by hand: no cluster, so it is the cluster.
            def standalone = !(pveGet('/cluster/status') ?: []).any { it.type?.toString() == 'cluster' }
            result = [collect: standalone, why: standalone ? 'standalone host' : 'no cluster role']
        } else if (role == 'active' && pveHostProp('pve.node') == null) {
            result = [collect: true, why: 'active cluster resource']
        } else if (role == 'active') {
            def status = ownStatus()
            def clustered = status.any { it.type?.toString() == 'cluster' }
            if (clustered && pveCorosyncRunning() == false) {
                result = [collect: false, why: 'active, but corosync is not running on this node']
            } else {
                def twoNode = status.count { it.type?.toString() == 'node' } == 2
                def ok = pveQuorate(status) || twoNode
                result = [collect: ok, why: ok ? 'active' : 'active, but this node has no quorum']
            }
        } else if (role == 'standby' && active?.startsWith('address:')) {
            def down = pveAddressDown(active.substring('address:'.length()))
            result = pveHandBack(label, [collect: down, why: down ? 'standby, cluster address down' : 'standby, cluster address answers'])
        } else if (role == 'standby' && active) {
            def status = ownStatus()
            if (pveCorosyncRunning() == false) {
                pveRoleResult = [collect: false, why: 'standby, but corosync is not running on this node', role: role]
                return pveRoleResult
            }
            def row = status.find { it.type?.toString() == 'node' && it.name?.toString() == active }
            // A two-node cluster loses quorum whenever either node is down, so there the standby
            // takes over without it; the active node, without quorum, has already stopped.
            def twoNode = status.count { it.type?.toString() == 'node' } == 2
            def takeOver = (pveQuorate(status) || twoNode) && (row == null || row.online?.toString() != '1')
            result = pveHandBack(label, [collect: takeOver, why: takeOver ? 'standby, ' + active + ' is offline' : 'standby, ' + active + ' is collecting'])
        } else {
            result = [collect: false, why: 'no cluster role']
        }
    } catch (Exception unreadable) {
        result = [collect: role == 'active', why: 'cluster role check failed: ' + unreadable.message]
    }
    pveRoleResult = result + [role: role]
    return pveRoleResult
}

// ------------------------------------------------------------ per-guest loops

// Each node's own API, from /cluster/status: node name -> base URL for its address.
def pveNodeBases = { List statusRows ->
    statusRows.findAll { it.type?.toString() == 'node' && it.name && it.ip }
        .collectEntries { [(it.name.toString()): pveHostUrl(it.ip.toString())] }
}

/*
 * One read per guest, for the loops that scale with guest count (a config, a snapshot list,
 * a guest agent's addresses). GET /nodes/<node>/<type>/<vmid><suffix> for every guest, and
 * call onResult(guest, data, error) for every guest in their order, on this thread; error is
 * null when the read succeeded, and says why when it did not happen or failed.
 *
 * - The read goes to the node that owns the guest, at its own address, when that address is
 *   one pve.api.hosts lists -- the cluster API address would only proxy it there. If the
 *   node's own address does not answer, its reads go through the cluster API instead.
 * - A guest on a node /cluster/status reports offline is not asked: it cannot answer.
 * - A per-node circuit breaker: a node's first failed read skips the rest of its guests for
 *   this run, so a sick node costs one timeout, not one per guest, and stderr gets one line.
 *   A connection failure or timeout always trips it; an HTTP error status only when
 *   httpErrorTrips is true. A guest agent that is not running answers HTTP 500 for its own
 *   guest alone, so agent reads pass false.
 * - At most pve.api.concurrency reads (default 2) in flight per node, so a node's own API
 *   workers, which the Proxmox UI shares, are never crowded. With more than one in flight,
 *   reads already started when a failure trips the breaker still finish.
 * - A time budget, pve.api.budget.ms (default 45000), below the Collector's script timeout:
 *   reads not started by then are skipped and counted on stderr, never fabricated.
 */
def pveForEachGuest = { String what, List guests, List statusRows, String suffix, Closure onResult,
                        boolean httpErrorTrips = true ->
    def concurrency = Math.max(1, pveHostProp('pve.api.concurrency', '2') as int)
    def deadline = System.currentTimeMillis() + (pveHostProp('pve.api.budget.ms', '45000') as long)
    def listed = pveHostProp('pve.api.hosts', '').tokenize(',').collect { pveHostUrl(it) } as Set
    def ownBase = pveNodeBases(statusRows).findAll { node, base -> base in listed }
    // Offline only where /cluster/status says so: a node it does not list is asked anyway.
    def offline = statusRows.findAll { it.type?.toString() == 'node' && it.online != null &&
                                       it.online?.toString() != '1' }
                            .collect { it.name?.toString() } as Set
    def tripped = new java.util.concurrent.ConcurrentHashMap()
    def viaCluster = new java.util.concurrent.ConcurrentHashMap()
    def overBudget = new java.util.concurrent.atomic.AtomicInteger()
    def pools = [:]

    def readOne = { Map guest, String node ->
        def path = '/nodes/' + node + '/' + guest.type + '/' + guest.vmid + suffix
        if (System.currentTimeMillis() > deadline) {
            overBudget.incrementAndGet()
            return [error: new IOException('time budget reached')]
        }
        if (tripped.containsKey(node)) { return [error: new IOException('reads stopped for node ' + node)] }
        try {
            def base = ownBase[node]
            if (base && !viaCluster.containsKey(node)) {
                try {
                    return [data: pveGetAt(base, path)]
                } catch (IOException ownFailure) {
                    if (pveFailureStatus(ownFailure) != null) { throw ownFailure }
                    viaCluster.put(node, true)
                }
            }
            return [data: pveGet(path)]
        } catch (Exception failure) {
            if (httpErrorTrips || pveFailureStatus(failure) == null) {
                if (tripped.putIfAbsent(node, true) == null) {
                    System.err.println(what + ' stopped for node ' + node + ' after ' + guest.type + '/' +
                        guest.vmid + ' failed (' + failure.message + '); its remaining guests are not read this run.')
                }
            }
            return [error: failure]
        }
    }

    def futures = guests.collect { guest ->
        def node = guest.node?.toString()
        if (!guest.vmid || node in offline) { return null }
        def pool = pools.computeIfAbsent(node) { java.util.concurrent.Executors.newFixedThreadPool(concurrency) }
        pool.submit({ -> readOne(guest, node) } as java.util.concurrent.Callable)
    }
    try {
        guests.eachWithIndex { guest, i ->
            def outcome = futures[i] == null ? [error: new IOException('node ' + guest.node + ' is offline')] : futures[i].get()
            onResult(guest, outcome.data, outcome.error)
        }
    } finally {
        pools.values()*.shutdownNow()
    }
    if (overBudget.get() > 0) {
        System.err.println(what + ': time budget reached: ' + overBudget.get() + ' guests not read this run.')
    }
}
