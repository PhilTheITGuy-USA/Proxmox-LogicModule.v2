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

/*
 * A BatchScript's discovered instances, from the datasourceinstanceProps binding LogicMonitor's
 * recipes describe (instance -> its properties): [wildvalue, prop], where prop(key) reads a
 * property with or without its auto. prefix. Empty where the binding is absent.
 */
def pveBatchInstances = { ->
    def instances = binding.hasVariable('datasourceinstanceProps') ? datasourceinstanceProps : null
    if (!(instances instanceof Map)) { return [] }
    instances.collect { name, props ->
        def prop = { String key ->
            def value = props?.get(key) ?: props?.get('auto.' + key)
            value == null || value.toString().trim().isEmpty() ? null : value.toString().trim()
        }
        [wildvalue: prop('wildvalue'), prop: prop]
    }
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
    if (status == 401) {
        throw new IOException('HTTP 401 for ' + path +
            ' - the API token in pve.token.credential was rejected. Check its secret, and that it has a' +
            ' PVEAuditor ACL. A token with privilege separation enabled needs its own ACL entry,' +
            ' not just the one on its user.')
    }
    // A 403 is a valid token short of a privilege; Proxmox names it in the status line
    if (status == 403) {
        throw new IOException('HTTP 403 for ' + path + ' (' + connection.responseMessage + ')' +
            ' - the token in pve.token.credential lacks that privilege. The suite needs PVEAuditor on /.')
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
 * The last poll waits for viewOk -- this node quorate, or one of two nodes -- keeping the
 * record: a node split from the majority would clear alerts with a stale view (M4).
 */
def pveHandBack = { String label, Map result, Closure viewOk ->
    if (!label) { return result }
    def key = 'handback:' + (pveHostProp('pve.cluster') ?: '') + ':' +
              (pveHostProp('pve.node') ?: pveHostProp('system.hostname') ?: '') + ':' + label
    def state = pveState()
    if (result.collect) {
        state.write(key, 'collecting', 7200)
        return result
    }
    if (state.read(key) && viewOk()) {
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
            result = pveHandBack(label, [collect: down, why: down ? 'standby, cluster address down' : 'standby, cluster address answers'], {
                def status = ownStatus()
                pveQuorate(status) || status.count { it.type?.toString() == 'node' } == 2
            })
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
            result = pveHandBack(label, [collect: takeOver, why: takeOver ? 'standby, ' + active + ' is offline' : 'standby, ' + active + ' is collecting'],
                                 { pveQuorate(status) || twoNode })
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

// ----------------------------------------------------------- topology model

/*
 * The map is modelled on LogicMonitor's vSphere suite: cluster -> node (Manage), node -> guest
 * (Compute), node and guest -> storage -> pool -> disk (Storage), node -> bridge [-> VLAN] ->
 * guest and node -> SDN zone -> VNet -> guest (Network). A vertex is drawn only where an instance or resource carries its key, so
 * each key below is built here once and used by both the TopologySource and the DataSource
 * discovery that carries it; the two must agree byte for byte.
 */
def pveTopoVertexKey = { String cluster, List parts -> pveTopoFold(['proxmoxve', cluster] + parts) }

/*
 * The cluster vertex's key. The portal keeps a vertex record per key that outlives the
 * instances carrying it, with the type it was first drawn as: deleting and rediscovering the
 * carrier re-attached the same record, still typed Cluster (lab, 2026-10-28). A key of its own
 * shape, proxmoxve--cluster--<name>, gives the cluster a fresh record.
 */
def pveTopoClusterKey = { String cluster -> pveTopoFold(['proxmoxve', 'cluster', cluster]) }

/*
 * local, shared or network. Network: the store lives on another system (NFS, SMB, GlusterFS,
 * iSCSI, Proxmox Backup Server, or Ceph with a monhost, which is an external cluster). Shared:
 * this cluster's own Ceph, or a store Proxmox is told every node sees (the shared flag).
 * Local: everything else, one per node.
 */
def pveStorageKind = { String type, shared, Map cfg ->
    def kind = type?.toLowerCase()
    if (kind in ['nfs', 'cifs', 'glusterfs', 'iscsi', 'iscsidirect', 'pbs', 'esxi']) { return 'network' }
    if (kind in ['rbd', 'cephfs']) { return cfg?.monhost ? 'network' : 'shared' }
    shared?.toString() in ['true', '1'] ? 'shared' : 'local'
}

// A local store is one vertex per node; a shared or network store one for the cluster.
def pveStorageKey = { String cluster, String kind, String node, String storage ->
    kind == 'local' ? pveTopoVertexKey(cluster, ['storage', node, storage])
                    : pveTopoVertexKey(cluster, ['storage', storage])
}

/*
 * /cluster/resources?type=storage lists a store once per node. Each row's kind and key, and
 * which rows carry the key: every row of a local store, and of a shared or network store only
 * the row of the first node by name, so one key sits on one instance.
 */
def pveStorageVertices = { String cluster, List rows, List cfgList ->
    def cfgByName = (cfgList ?: []).collectEntries { [(it.storage?.toString()): it] }
    def result = [:]
    (rows ?: []).findAll { it.storage && it.node }.groupBy { it.storage.toString() }.each { name, list ->
        def kind = pveStorageKind(list[0].plugintype?.toString(), list[0].shared, cfgByName[name])
        def carrier = list.min { it.node.toString() }
        list.each { row ->
            result[row.id?.toString()] = [kind: kind, storage: name, node: row.node.toString(),
                key: pveStorageKey(cluster, kind, row.node.toString(), name),
                carries: kind == 'local' || row.is(carrier)]
        }
    }
    result
}

def pvePoolKey = { String cluster, String node, String kind, String name ->
    pveTopoVertexKey(cluster, ['pool', node, kind, name])
}

def pveDiskKey = { String cluster, String node, String devpath ->
    pveTopoVertexKey(cluster, ['disk', node, devpath.replaceAll('^/dev/', '')])
}

/*
 * The node's bridges, Linux or Open vSwitch: the virtual switches its guests attach to, on
 * the map as node -> bridge -> guest, or node -> bridge -> VLAN -> guest for a tagged NIC
 * (A§3.4, amended 2026-10-30). Ports, bonds and host VLAN interfaces are left to the node's
 * SNMP interface monitoring (user, 2026-10-28).
 */
def pveNetBridges = { List rows ->
    (rows ?: []).findAll { it.iface && it.type?.toString() in ['bridge', 'OVSBridge'] }
}

// The bridge names a guest NIC can hang off: a name that is also an SDN VNet is the VNet.
def pveNodeBridgeNames = { List rows, Collection vnetNames ->
    pveNetBridges(rows).collect { it.iface.toString() }.findAll { !((vnetNames ?: []).contains(it)) } as Set
}

/*
 * A guest's NICs that name a bridge, in slot order (net2 before net10): [slot, bridge, tag],
 * the tag an integer 1-4094 or null. A NIC's keys come in any order; trunks= is ignored.
 */
def pveGuestNics = { Map config ->
    (config ?: [:]).keySet().findAll { it.toString() ==~ /net\d+/ }.sort { it.toString().substring(3) as int }.collect { slot ->
        def fields = config[slot].toString().tokenize(',').collectEntries { field ->
            def at = field.indexOf('=')
            at > 0 ? [(field.substring(0, at)): field.substring(at + 1)] : [:]
        }
        def tag = fields.tag?.isInteger() ? fields.tag as int : null
        fields.bridge ? [slot: slot.toString(), bridge: fields.bridge.toString(), tag: tag != null && tag >= 1 && tag <= 4094 ? tag : null] : null
    }.findAll()
}

def pveBridgeKey = { String cluster, String node, String bridge ->
    pveTopoVertexKey(cluster, ['net', node, bridge])
}

def pveVlanKey = { String cluster, String node, String bridge, int tag ->
    pveTopoVertexKey(cluster, ['net', node, bridge, 'vlan' + tag])
}

/*
 * The VLANs this node's guest NICs use on its own bridges, [bridge, tag], for
 * Proxmox_VE_Node_Network's discovery: the node's guest lists and each guest's config, asked
 * of the node itself, templates left out as the topology leaves them out. A list or config
 * that cannot be read costs that guest's VLANs, said once on stderr, never the run.
 */
def pveNodeVlans = { String node, Set bridgeNames, Collection vnetNames ->
    def deadline = System.currentTimeMillis() + (pveHostProp('pve.api.budget.ms', '45000') as long)
    def found = [] as Set
    def skipped = 0
    ['qemu', 'lxc'].each { type ->
        def guests
        try {
            guests = pveNodeGet('/nodes/' + node + '/' + type) ?: []
        } catch (Exception failure) {
            System.err.println('VLANs: no ' + type + ' list from ' + node + ': ' + failure.message)
            return
        }
        guests.findAll { it.vmid != null && !(it.template?.toString() in ['1', 'true']) }.each { guest ->
            if (System.currentTimeMillis() > deadline) { skipped++; return }
            try {
                pveGuestNics(pveNodeGet('/nodes/' + node + '/' + type + '/' + guest.vmid + '/config') as Map).each { nic ->
                    if (nic.tag != null && nic.bridge in bridgeNames && !((vnetNames ?: []).contains(nic.bridge))) {
                        found << [bridge: nic.bridge, tag: nic.tag]
                    }
                }
            } catch (Exception failure) {
                System.err.println('VLANs: ' + type + '/' + guest.vmid + ' config not read: ' + failure.message)
            }
        }
    }
    if (skipped) { System.err.println('VLANs: time budget reached (pve.api.budget.ms): ' + skipped + ' guest configs not read') }
    found.sort { a, b -> a.bridge <=> b.bridge ?: a.tag <=> b.tag }
}

def pveSdnKey = { String cluster, String zone, String vnet ->
    pveTopoVertexKey(cluster, vnet ? ['sdn', zone, vnet] : ['sdn', zone])
}

/*
 * The cluster name a discovery keys its instances' vertices under, or null when it should
 * stamp none. Keys are written plain, not through lm.topo, so this stays testable here; with
 * topo.namespace or topo.blacklist set the TopologySource's keys change and these would stop
 * matching, so the feature steps aside and says why. A cluster module stamps only where it
 * collects (a standby's keys would double every vertex); a node module reads the name from
 * its own node, which answers with the same cluster row the TopologySource reads.
 */
def pveCarrierCluster = { String what, boolean nodeScoped ->
    def blocking = ['topo.namespace', 'topo.blacklist'].findAll { pveHostProp(it) }
    if (blocking) {
        System.err.println(what + ' ERIs skipped: ' + blocking.join(' and ') +
            ' is set, which rewrites the topology keys these would have to match.')
        return null
    }
    if (!nodeScoped && !pveRole().collect) { return null }
    try {
        pveTopoClusterName((nodeScoped ? pveNodeGet('/cluster/status') : pveGet('/cluster/status')) ?: [])
    } catch (Exception failure) {
        System.err.println(what + ' ERIs skipped: ' + failure.message)
        null
    }
}

// The two instance properties that put an instance on the map.
def pveEri = { String key, String type -> key ? ['predef.externalResourceID': key, 'predef.externalResourceType': type] : [:] }

/*
 * This node's storage pools, for Proxmox_VE_Node_StoragePools' discovery and collection
 * alike: ZFS pools, LVM volume groups and LVM thin pools, sizes in bytes. A node without
 * one kind (no ZFS installed) answers an error for it, which skips that kind and says so; a
 * node that cannot be reached at all fails the run.
 */
def pveNodePools = { String node ->
    def read = { String path ->
        try {
            pveNodeGet('/nodes/' + node + path)
        } catch (IOException failure) {
            if (pveFailureStatus(failure) == null) { throw failure }
            System.err.println(path + ' not read on ' + node + ': ' + failure.message)
            null
        }
    }
    def bytes = { value -> (value ?: 0) as long }
    def pools = []
    (read('/disks/zfs') ?: []).findAll { it.name }.each { zfs ->
        pools << [id: 'zfs/' + zfs.name, kind: 'zfs', name: zfs.name.toString(), label: 'ZFS pool',
                  size: bytes(zfs.size), used: bytes(zfs.alloc), free: bytes(zfs.free),
                  health: zfs.health?.toString(), frag: zfs.frag]
    }
    def lvm = read('/disks/lvm')
    ((lvm instanceof Map ? lvm.children : null) ?: []).findAll { it.name }.each { vg ->
        pools << [id: 'lvm/' + vg.name, kind: 'lvm', name: vg.name.toString(), label: 'LVM volume group',
                  size: bytes(vg.size), used: bytes(vg.size) - bytes(vg.free), free: bytes(vg.free)]
    }
    (read('/disks/lvmthin') ?: []).findAll { it.vg && it.lv }.each { thin ->
        pools << [id: 'lvmthin/' + thin.vg + '/' + thin.lv, kind: 'lvmthin', name: thin.vg + '/' + thin.lv,
                  label: 'LVM thin pool', size: bytes(thin.lv_size), used: bytes(thin.used),
                  free: bytes(thin.lv_size) - bytes(thin.used),
                  metaSize: bytes(thin.metadata_size), metaUsed: bytes(thin.metadata_used)]
    }
    pools
}

/*
 * This cluster's Ceph pools, from the first online node that answers (every node answers for
 * the whole cluster), or null on a cluster without Ceph. Pools whose names start with a dot,
 * such as .mgr, are Ceph's own and are left out.
 */
def pveCephPools = { ->
    def nodes = (pveGet('/cluster/resources?type=node') ?: []).findAll { it.status?.toString() == 'online' && it.node }
    for (node in nodes) {
        try {
            def pools = pveGet('/nodes/' + node.node + '/ceph/pool')
            if (pools != null) { return pools.findAll { it.pool_name && !it.pool_name.toString().startsWith('.') } }
        } catch (Exception unavailable) {
            System.err.println('Ceph pools unavailable from ' + node.node + ': ' + unavailable.message)
        }
    }
    null
}

/*
 * SDN zones and VNets, or null where SDN is not set up (the API then answers an error). A
 * connection failure is not "no SDN" and is thrown.
 */
def pveSdn = { ->
    try {
        [zones: (pveGet('/cluster/sdn/zones') ?: []).findAll { it.zone },
         vnets: (pveGet('/cluster/sdn/vnets') ?: []).findAll { it.vnet && it.zone }]
    } catch (IOException failure) {
        if (pveFailureStatus(failure) == null) { throw failure }
        System.err.println('No SDN configuration: ' + failure.message)
        null
    }
}

/*
 * The storage a guest's disks and mount points use, from its config: every rootfs, mpN,
 * scsiN, virtioN, sataN, ideN, efidiskN and tpmstateN entry. Each is [storage: name] for a
 * volume on a Proxmox store, [path: ...] for a host directory bind-mounted into a container,
 * or [device: ...] for a disk passed straight through. CD-ROM media and unused volumes are not
 * counted: neither is storage the guest runs on. Nor is USB passthrough (a VM's usbN, a
 * container's devN under /dev/bus/usb): the config names no more than a vendor and product
 * ID, so a Zigbee stick would draw as storage (spike S9, lab 2026-10-29).
 */
def pveGuestVolumes = { Map config ->
    def found = []
    (config ?: [:]).each { key, value ->
        def slot = key.toString()
        def text = value?.toString() ?: ''
        if (slot ==~ /usb\d+/) { return }
        if (slot ==~ /dev\d+/) {
            def path = text.tokenize(',')[0]
            if (!path?.startsWith('/dev/bus/usb/')) { found << [slot: slot, device: path] }
            return
        }
        if (!(slot ==~ /(rootfs|mp\d+|scsi\d+|virtio\d+|sata\d+|ide\d+|efidisk\d+|tpmstate\d+)/)) { return }
        if (text.contains('media=cdrom')) { return }
        def volume = text.tokenize(',').find { !it.contains('=') || it.startsWith('volume=') }
        volume = volume?.replaceFirst('^volume=', '')?.trim()
        if (!volume || volume == 'none') { return }
        if (volume.startsWith('/dev/')) { found << [slot: slot, device: volume] }
        else if (volume.startsWith('/')) { found << [slot: slot, path: volume] }
        else if (volume.contains(':')) { found << [slot: slot, storage: volume.substring(0, volume.indexOf(':'))] }
    }
    found
}

// The directory a store keeps its files in on a node: its path, or /mnt/pve/<name> where
// Proxmox mounts a network share itself.
def pveStoragePath = { Map cfg ->
    if (!cfg) { return null }
    if (cfg.path) { return cfg.path.toString().replaceAll('/+$', '') }
    cfg.type?.toString() in ['nfs', 'cifs', 'glusterfs', 'cephfs'] ? '/mnt/pve/' + cfg.storage : null
}

/*
 * The store a host path bind-mounted into a container lies on: the store whose directory
 * holds the path, or a store kept inside the bind-mounted directory, the longest match first.
 * A path of one segment (/mnt) contains too much to say, so only the first direction counts.
 */
def pveStoreForPath = { String path, List cfgs ->
    def bind = path?.replaceAll('/+$', '')
    if (!bind) { return null }
    def depth = bind.tokenize('/').size()
    def matches = (cfgs ?: []).collect { [cfg: it, dir: pveStoragePath(it)] }.findAll { it.dir }.findAll {
        bind == it.dir || bind.startsWith(it.dir + '/') || (depth >= 2 && it.dir.startsWith(bind + '/'))
    }
    matches ? matches.max { it.dir.length() }.cfg : null
}

/*
 * The physical disk a device name refers to, as the short name Node_Disks keys it by (sda,
 * nvme0n1): a disk itself, one of its partitions (sda3, nvme0n1p3, or a partition row's
 * parent), or a /dev/disk/by-id name, which carries the disk's serial or WWN. Null when no
 * disk on the node matches.
 */
def pveDiskOf = { String name, List rows ->
    def base = name?.tokenize('/')?.last()?.replaceFirst(/-part\d+$/, '')
    if (!base) { return null }
    def whole = (rows ?: []).findAll { it.devpath && !it.parent }
    def shortOf = { row -> row.devpath.toString().replaceAll('^/dev/', '') }
    def direct = whole.find { shortOf(it) == base }
    if (direct) { return shortOf(direct) }
    def part = (rows ?: []).find { it.parent && it.devpath?.toString()?.endsWith('/' + base) }
    if (part) { return part.parent.toString().replaceAll('^/dev/', '') }
    def parent = (base =~ /^(.*\d)p\d+$/).with { it.find() ? it.group(1) : null } ?:
                 (base =~ /^([a-z]+)\d+$/).with { it.find() ? it.group(1) : null }
    if (parent && whole.any { shortOf(it) == parent }) { return parent }
    def byId = whole.find {
        (it.serial && base.contains(it.serial.toString())) ||
            (it.wwn && base.contains(it.wwn.toString().replaceFirst('^0x', '')))
    }
    byId ? shortOf(byId) : null
}

// Every leaf device of a ZFS pool's vdev tree, as /nodes/{node}/disks/zfs/{pool} returns it.
def pveZfsLeaves = { tree ->
    def found = []
    def stack = [tree]
    while (stack) {
        def entry = stack.remove(stack.size() - 1)
        if (!(entry instanceof Map)) { continue }
        if (entry.children instanceof List && entry.children) { stack.addAll(entry.children) }
        else if (entry.name && entry.leaf?.toString() == '1') { found << entry.name.toString() }
    }
    found
}

/*
 * Everything the TopologySource needs from the API, read once: the cluster, its storage and
 * SDN definitions, every guest's config, and each online node's network, pools and disks.
 * A read that fails leaves its part empty and says so on stderr; the map draws what was
 * read. Per-node reads go through the cluster API, and a node whose first read cannot
 * connect is not asked again this run.
 */
def pveTopoInput = { ->
    def status = pveGet('/cluster/status') ?: []
    def optional = { String what, Closure read ->
        try { read() } catch (Exception failure) {
            System.err.println(what + ' not read: ' + failure.message)
            null
        }
    }
    def input = [
        status   : status,
        cluster  : pveTopoClusterName(status),
        nodes    : (pveGet('/cluster/resources?type=node') ?: []),
        storages : optional('Storage', { pveGet('/cluster/resources?type=storage') }) ?: [],
        storageCfg: optional('Storage definitions', { pveGet('/storage') }) ?: [],
        sdnZones : optional('SDN zones', { pveGet('/cluster/sdn/zones') }) ?: [],
        sdnVnets : optional('SDN VNets', { pveGet('/cluster/sdn/vnets') }) ?: [],
        configs  : [:],
        perNode  : [:],
        osdTree  : null,
        cephPools: []
    ]
    def guests = pveGuestList()
    input.guests = guests
    pveForEachGuest('Config reads', guests, status, '/config', { guest, config, error ->
        if (error == null && config instanceof Map) { input.configs[guest.id.toString()] = config }
    })

    def online = input.nodes.findAll { it.status?.toString() == 'online' && it.node }.collect { it.node.toString() }
    online.each { node ->
        def data = [:]
        def stopped = false
        def read = { String key, String path ->
            if (stopped) { return }
            try {
                data[key] = pveGet('/nodes/' + node + path)
            } catch (Exception failure) {
                System.err.println('Topology: ' + path + ' on ' + node + ' not read: ' + failure.message)
                if (pveFailureStatus(failure) == null) { stopped = true }
            }
        }
        read('network', '/network')
        read('disks', '/disks/list?include-partitions=1&skipsmart=1')
        read('dirs', '/disks/directory')
        read('lvm', '/disks/lvm')
        read('lvmthin', '/disks/lvmthin')
        read('zfs', '/disks/zfs')
        data.zfsDetail = [:]
        (data.zfs ?: []).each { pool ->
            if (!stopped && pool.name) {
                read('zfs:' + pool.name, '/disks/zfs/' + URLEncoder.encode(pool.name.toString(), 'UTF-8'))
                data.zfsDetail[pool.name.toString()] = data.remove('zfs:' + pool.name)
            }
        }
        input.perNode[node] = data
    }

    // Ceph answers the same from every node: ask online nodes in turn until one does.
    if (input.storageCfg.any { it.type?.toString() == 'rbd' && !it.monhost }) {
        for (node in online) {
            def tree = optional('Ceph OSD tree on ' + node, { pveGet('/nodes/' + node + '/ceph/osd') })
            if (tree) {
                input.osdTree = tree
                input.cephPools = optional('Ceph pools on ' + node, { pveGet('/nodes/' + node + '/ceph/pool') }) ?: []
                break
            }
        }
    }
    input
}

/*
 * The edges, as [type, from key, to key], from what pveTopoInput read. Pure, so the harness
 * can check every rule; the TopologySource only turns the keys into ERIs and prints them.
 */
def pveTopoPlan = { Map input ->
    def cluster = input.cluster
    def edges = new LinkedHashSet()
    def edge = { String type, String from, String to -> if (from && to && from != to) { edges << [type, from, to] } }

    def status = input.status ?: []
    def clustered = status.any { it.type?.toString() == 'cluster' }
    def ips = status.findAll { it.type?.toString() == 'node' && it.name }
        .collectEntries { [(it.name.toString()): it.ip?.toString()] }
    def nodeKey = { String node -> pveTopoNodeVertexKey(cluster, node, ips[node]) }
    def clusterKey = clustered ? pveTopoClusterKey(cluster) : null

    // Manage: the cluster above every node, offline ones too.
    def nodeNames = (input.nodes ?: []).collect { it.node?.toString() }.findAll { it }
    nodeNames.each { node -> if (clusterKey) { edge('Manage', clusterKey, nodeKey(node)) } }

    // Storage: each node to every store it lists.
    def stores = pveStorageVertices(cluster, input.storages, input.storageCfg)
    def cfgs = input.storageCfg ?: []
    def cfgByName = cfgs.collectEntries { [(it.storage?.toString()): it] }
    def storeKeyOn = { String node, String name ->
        def row = stores.values().find { it.storage == name && it.node == node } ?:
                  stores.values().find { it.storage == name }
        row ? (row.kind == 'local' ? pveStorageKey(cluster, 'local', node, name) : row.key)
            : pveStorageKey(cluster, pveStorageKind(cfgByName[name]?.type?.toString(), cfgByName[name]?.shared, cfgByName[name]), node, name)
    }
    stores.values().each { row -> edge('Storage', nodeKey(row.node), row.key) }

    // Storage: each store to the pool or disk under it, on every node that lists it.
    stores.values().each { row ->
        def cfg = cfgByName[row.storage]
        def data = input.perNode?.get(row.node) ?: [:]
        switch (cfg?.type?.toString()) {
            case 'zfspool':
                def pool = cfg.pool?.toString()?.tokenize('/')?.getAt(0)
                if (pool) { edge('Storage', row.key, pvePoolKey(cluster, row.node, 'zfs', pool)) }
                break
            case 'lvmthin':
                if (cfg.vgname && cfg.thinpool) {
                    edge('Storage', row.key, pvePoolKey(cluster, row.node, 'lvmthin', cfg.vgname + '/' + cfg.thinpool))
                }
                break
            case 'lvm':
                if (cfg.vgname) { edge('Storage', row.key, pvePoolKey(cluster, row.node, 'lvm', cfg.vgname.toString())) }
                break
            case 'rbd':
                if (!cfg.monhost) { edge('Storage', row.key, pveTopoVertexKey(cluster, ['cephpool', (cfg.pool ?: 'rbd').toString()])) }
                break
            case 'dir':
                def dir = pveStoragePath(cfg)
                def mount = (data.dirs ?: []).findAll { it.path && (dir == it.path || dir?.startsWith(it.path.toString() + '/')) }
                    .max { it.path.toString().length() }
                def disk = mount ? pveDiskOf(mount.device?.toString(), data.disks) : null
                if (disk) { edge('Storage', row.key, pveDiskKey(cluster, row.node, disk)) }
                break
        }
    }

    // Storage: thin pool -> volume group -> disk, and ZFS pool -> disk, per node.
    (input.perNode ?: [:]).each { node, data ->
        (data.lvmthin ?: []).each { thin ->
            if (thin.vg && thin.lv) {
                edge('Storage', pvePoolKey(cluster, node, 'lvmthin', thin.vg + '/' + thin.lv),
                     pvePoolKey(cluster, node, 'lvm', thin.vg.toString()))
            }
        }
        ((data.lvm instanceof Map ? data.lvm.children : null) ?: []).each { vg ->
            (vg.children ?: []).each { pv ->
                def disk = pveDiskOf(pv.name?.toString(), data.disks)
                if (vg.name && disk) { edge('Storage', pvePoolKey(cluster, node, 'lvm', vg.name.toString()), pveDiskKey(cluster, node, disk)) }
            }
        }
        (data.zfsDetail ?: [:]).each { pool, tree ->
            pveZfsLeaves(tree).each { leaf ->
                def disk = pveDiskOf(leaf, data.disks)
                if (disk) { edge('Storage', pvePoolKey(cluster, node, 'zfs', pool), pveDiskKey(cluster, node, disk)) }
            }
        }
        // A Ceph OSD on its disk.
        (data.disks ?: []).findAll { !it.parent && it.devpath }.each { disk ->
            def ids = ((disk.'osdid-list' ?: []) + [disk.osdid]).findAll { it != null && (it as int) >= 0 }.unique()
            ids.each { id ->
                edge('Storage', pveTopoVertexKey(cluster, ['osd', id.toString()]),
                     pveDiskKey(cluster, node, disk.devpath.toString()))
            }
        }
    }

    // Storage: each node to the OSDs CRUSH places on it.
    def osdStack = input.osdTree?.root ? [input.osdTree.root] : []
    while (osdStack) {
        def entry = osdStack.remove(osdStack.size() - 1)
        if (!(entry instanceof Map)) { continue }
        if (entry.type?.toString() == 'osd' && entry.host && entry.id != null) {
            edge('Storage', nodeKey(entry.host.toString()), pveTopoVertexKey(cluster, ['osd', entry.id.toString()]))
        }
        if (entry.children instanceof List) { osdStack.addAll(entry.children) }
    }

    // Network: SDN zones and VNets, cluster-wide, a zone under each node it spans and never
    // under a bridge (a bridge -> zone edge joins two instances, which the cluster-wide map does
    // not draw). Each node to its bridges (A§3.4, amended 2026-10-30); ports and bonds are kept
    // off the map (user, 2026-10-28): a switch joins the map at the node, by its MACs.
    def vnets = (input.sdnVnets ?: []).findAll { it.vnet && it.zone }.collectEntries { [(it.vnet.toString()): it.zone.toString()] }
    def bridgesOf = (input.perNode ?: [:]).collectEntries { node, data -> [(node): pveNodeBridgeNames(data?.network, vnets.keySet())] }
    bridgesOf.each { node, names -> names.sort().each { edge('Network', nodeKey(node), pveBridgeKey(cluster, node, it)) } }
    (input.sdnZones ?: []).findAll { it.zone }.each { zone ->
        def zoneKey = pveSdnKey(cluster, zone.zone.toString(), null)
        def spans = zone.nodes ? zone.nodes.toString().tokenize(',').collect { it.trim() } : nodeNames
        spans.findAll { it in nodeNames }.each { node -> edge('Network', nodeKey(node), zoneKey) }
        vnets.findAll { it.value == zone.zone.toString() }.each { vnet, z ->
            edge('Network', zoneKey, pveSdnKey(cluster, z, vnet))
        }
    }

    // Each guest whose config was read: Compute from its node, Storage to its stores (or from
    // its node, for a path or device no store covers), Network from its VNets, bridges and VLANs.
    (input.guests ?: []).each { guest ->
        def config = input.configs?.get(guest.id?.toString())
        def node = guest.node?.toString()
        if (!(config instanceof Map) || !node || !guest.vmid) { return }
        def guestKey = pveGuestMac(config) ?: pveTopoKey(cluster, guest.type + '-' + guest.vmid)
        edge('Compute', nodeKey(node), guestKey)
        def nodeCfgs = cfgs.findAll { cfg -> stores.values().any { it.storage == cfg.storage?.toString() && it.node == node } }
        pveGuestVolumes(config).each { volume ->
            if (volume.storage) {
                edge('Storage', guestKey, storeKeyOn(node, volume.storage))
            } else if (volume.path && pveStoreForPath(volume.path, nodeCfgs)) {
                edge('Storage', guestKey, storeKeyOn(node, pveStoreForPath(volume.path, nodeCfgs).storage.toString()))
            } else {
                edge('Storage', nodeKey(node), guestKey)
            }
        }
        // A NIC on a VNet joins the VNet; on a bridge its node lists, the bridge or, tagged, the
        // bridge's VLAN; on anything else, nothing.
        pveGuestNics(config).each { nic ->
            if (vnets.containsKey(nic.bridge)) {
                edge('Network', pveSdnKey(cluster, vnets[nic.bridge], nic.bridge), guestKey)
            } else if (nic.bridge in (bridgesOf[node] ?: [])) {
                def bridgeKey = pveBridgeKey(cluster, node, nic.bridge)
                if (nic.tag == null) {
                    edge('Network', bridgeKey, guestKey)
                } else {
                    def vlanKey = pveVlanKey(cluster, node, nic.bridge, nic.tag)
                    edge('Network', bridgeKey, vlanKey)
                    edge('Network', vlanKey, guestKey)
                }
            }
        }
    }
    edges as List
}
