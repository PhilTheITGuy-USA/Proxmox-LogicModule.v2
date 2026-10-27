/*
 * Runs the assembled module scripts against a mock Proxmox VE API.
 *
 * These scripts cannot be run against a real cluster on demand, and the only other
 * verification available is a live Collector. This harness closes that gap: it serves
 * recorded API shapes over HTTP, executes each script with the bindings a Collector
 * would provide, and asserts the output actually parses the way LogicMonitor expects --
 * discovery line format, BatchScript instance.datapoint=value format, numeric values,
 * and complete coverage of every datapoint the module declares.
 *
 * Run with: docker compose -f tests/docker-compose.yml run --rm tests
 */
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

final String TOKEN = 'monitor@pam!logicmonitor=00000000-0000-0000-0000-000000000000'
final File ROOT = new File(System.getenv('PVE_MODULE_ROOT') ?: '/work')

def fixtures = new JsonSlurper().parse(new File(ROOT, 'tests/fixtures/pve_api.json'))

// ------------------------------------------------------------------ mock API

def requested = [] as Set
// Every request in order, duplicates kept, so a case can look at only what it caused.
def requestLog = Collections.synchronizedList([])
// A second address serving the same fixtures, standing in for another node: failover and
// owner-node requests go there, and its own log shows which requests did.
def server2Requests = Collections.synchronizedList([])
def serve = { exchange, List log ->
    def uri = exchange.requestURI
    def key = uri.path.replaceFirst('^/api2/json', '') + (uri.query ? '?' + uri.query : '')
    requested << key
    log << key

    String body
    int status
    if (exchange.requestHeaders.getFirst('Authorization') != 'PVEAPIToken=' + TOKEN) {
        status = 401
        body = JsonOutput.toJson([data: null, errors: [auth: 'invalid token']])
    } else if (fixtures.containsKey(key)) {
        status = 200
        body = JsonOutput.toJson([data: fixtures[key]])
    } else {
        // A 501 rather than a 404 so a missing fixture is obviously a harness gap.
        status = 501
        body = JsonOutput.toJson([data: null, errors: [path: 'no fixture for ' + key]])
    }

    byte[] bytes = body.getBytes('UTF-8')
    exchange.responseHeaders.add('Content-Type', 'application/json')
    exchange.sendResponseHeaders(status, bytes.length)
    exchange.responseBody.withStream { it.write(bytes) }
}
def server = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
server.createContext('/api2/json') { exchange -> serve(exchange, requestLog) }
server.executor = java.util.concurrent.Executors.newSingleThreadExecutor()
def server2 = HttpServer.create(new InetSocketAddress('127.0.0.1', 0), 0)
server2.createContext('/api2/json') { exchange -> serve(exchange, server2Requests) }
server2.executor = java.util.concurrent.Executors.newSingleThreadExecutor()

/*
 * Run body with some fixtures replaced. A null value removes that key, so the mock
 * answers it with 501. Everything is put back afterwards, keys that did not exist
 * included, so one case cannot leak its fixtures into the next.
 */
def withFixtures = { Map overrides, Closure body ->
    def saved = [:]
    def absent = []
    overrides.each { key, value ->
        if (fixtures.containsKey(key)) { saved[key] = fixtures[key] } else { absent << key }
        if (value == null) { fixtures.remove(key) } else { fixtures[key] = value }
    }
    try {
        return body()
    } finally {
        saved.each { key, value -> fixtures[key] = value }
        absent.each { fixtures.remove(it) }
    }
}
server.start()
server2.start()
def baseUrl = 'http://127.0.0.1:' + server.address.port

// ------------------------------------------------------------------- runner

def failures = []
int checks = 0

def note = { String scope, boolean ok, String message ->
    checks++
    if (!ok) { failures << (scope + ': ' + message) }
}

/* Execute a script with the bindings a Collector injects, capturing its streams. */
def runScript = { File file, Map bindings ->
    def outBuffer = new ByteArrayOutputStream()
    def errBuffer = new ByteArrayOutputStream()
    def realOut = System.out
    def realErr = System.err
    def result = null
    Throwable thrown = null
    try {
        System.setOut(new PrintStream(outBuffer, true, 'UTF-8'))
        System.setErr(new PrintStream(errBuffer, true, 'UTF-8'))
        result = new GroovyShell(new Binding(bindings)).evaluate(file)
    } catch (Throwable t) {
        thrown = t
    } finally {
        System.setOut(realOut)
        System.setErr(realErr)
    }
    [
        exit  : result,
        stdout: new String(outBuffer.toByteArray(), 'UTF-8'),
        stderr: new String(errBuffer.toByteArray(), 'UTF-8'),
        thrown: thrown,
    ]
}

def hostProps = [
    'system.hostname': '127.0.0.1',
    'pve.api.url'    : baseUrl,
    'pve.token.credential'  : TOKEN,
    'pve.cluster.role'      : 'active',
    'pve.api.timeout': '5000',
]

// A node resource as the NetScan writes it: reached at its own hostname. pve.api.url points
// nowhere, so a node module that asked the cluster API would fail; it supplies only the scheme.
def nodeHostProps = hostProps + ['pve.api.url': 'http://127.0.0.1:1', 'pve.node': 'pve1',
                                 'system.hostname': '127.0.0.1:' + server.address.port]
def NODE_SCOPED = ['Proxmox_VE_Node_Disks', 'Proxmox_VE_Node_Services', 'Proxmox_VE_Node_Certificates',
                   'Proxmox_VE_Node_Replication', 'Proxmox_VE_Node_Subscription', 'Proxmox_VE_Node_Detail']

// Modules whose collection calls each guest's own node, so a guest on an offline node reports nothing.
def GUEST_NODE_READ_MODULES = ['Proxmox_VE_Cluster_Snapshots']
def AD_LINE = ~/^([A-Za-z0-9_.\-]+)##([^#]*)##([^#]*)####(.*)$/
def BATCH_LINE = ~/^([A-Za-z0-9_.\-]+)\.([A-Za-z0-9_]+)=(.*)$/
def PLAIN_LINE = ~/^([A-Za-z0-9_]+)=(.*)$/
def isNumeric = { String v -> v ==~ /^-?\d+(\.\d+)?([eE][-+]?\d+)?$/ }

// Instance properties a Collector supplies for per-instance Script collection.
def instanceBindings = [:]

def modulesDir = new File(ROOT, 'modules')
def scriptsDir = new File(ROOT, 'dist/scripts')

// --------------------------------------------------- per-module conformance

modulesDir.listFiles({ f -> f.name.endsWith('.json') } as FileFilter).sort { it.name }.each { defFile ->
    def defn = new JsonSlurper().parse(defFile)
    String name = defn.name

    /*
     * A TopologySource has no datapoints and prints edges rather than metrics, and its
     * script is the one in the suite that cannot run here at all -- it works through
     * Collector-only snippet classes, which is why build.py emits it outside
     * dist/scripts/. Its Proxmox-side logic lives in the preamble helpers instead, and
     * those are asserted further down like anything else.
     */
    if (defn.moduleType in ['topologysource', 'propertysource']) {
        return
    }

    def declared = defn.datapoints.collectEntries { [(it.name): it.conditional as boolean] }
    def required = declared.findAll { key, conditional -> !conditional }.keySet()

    def discovered = []
    def onOfflineNode = [] as Set
    if (defn.discoveryScript) {
        def run = runScript(new File(scriptsDir, name + '.ad.groovy'), [hostProps: name in NODE_SCOPED ? nodeHostProps : hostProps])

        note("${name}/ad", run.thrown == null, "threw ${run.thrown}")
        note("${name}/ad", run.exit == 0, "exit was ${run.exit}, expected 0")

        def lines = run.stdout.readLines().findAll { it.trim() }
        note("${name}/ad", !lines.isEmpty(), 'discovered no instances')

        lines.each { line ->
            def matcher = AD_LINE.matcher(line)
            if (!matcher.matches()) {
                note("${name}/ad", false, "malformed discovery line: ${line}")
                return
            }
            discovered << matcher.group(1)
            if (matcher.group(4).tokenize('&').contains('auto.pve_node=pve2')) { onOfflineNode << matcher.group(1) }
            // An instance without the grouping property falls outside every node group.
            if (defn.instanceGroupProperty) {
                note("${name}/ad", matcher.group(4).tokenize('&')
                         .any { it.startsWith(defn.instanceGroupProperty + '=') },
                     "instance ${matcher.group(1)} lacks ${defn.instanceGroupProperty}, " +
                     'which its instance group is built from')
            }
            note("${name}/ad", !matcher.group(1).contains(' '),
                 "instance id contains a space: ${matcher.group(1)}")
            // The two predef ERI properties are the only others: GuestStatus and Cluster put
            // the topology keys on their instances, by default since 2026-10-25.
            matcher.group(4).tokenize('&').each { pair ->
                note("${name}/ad", (pair.startsWith('auto.') ||
                                    pair.startsWith('predef.externalResourceID=') ||
                                    pair.startsWith('predef.externalResourceType=')) && pair.contains('='),
                     "instance property is not auto.key=value: ${pair}")
                note("${name}/ad", pair.split('=', 2)[1].trim() != '',
                     "instance property has a blank value: ${pair}")
            }
        }
    }

    // The built export carries the grouping LogicMonitor reads: groupMethod ilp plus the
    // property in groupExpr, exactly as a portal export of a hand-grouped module shows it.
    if (defn.discoveryScript) {
        def built = new JsonSlurper().parse(new File(ROOT, 'dist/' + name + '.json')).activeDiscovery
        def wantMethod = defn.instanceGroupProperty ? 'ilp' : 'none'
        note("${name}/ad", built.groupMethod == wantMethod && built.groupExpr == defn.instanceGroupProperty,
             "built groupMethod/groupExpr were ${built.groupMethod}/${built.groupExpr}, " +
             "expected ${wantMethod}/${defn.instanceGroupProperty}")
    }

    def bindings = [hostProps: name in NODE_SCOPED ? nodeHostProps : hostProps]
    if (instanceBindings[name]) { bindings.taskProps = instanceBindings[name] }
    def run = runScript(new File(scriptsDir, name + '.collect.groovy'), bindings)

    note("${name}/collect", run.thrown == null, "threw ${run.thrown}")
    note("${name}/collect", run.exit == 0, "exit was ${run.exit}, expected 0")

    def lines = run.stdout.readLines().findAll { it.trim() }
    note("${name}/collect", !lines.isEmpty(), 'produced no output')

    if (defn.collectionMethod == 'batchscript') {
        def perInstance = [:].withDefault { [] as Set }
        lines.each { line ->
            def matcher = BATCH_LINE.matcher(line)
            if (!matcher.matches()) {
                note("${name}/collect", false, "malformed batchscript line: ${line}")
                return
            }
            perInstance[matcher.group(1)] << matcher.group(2)
            note("${name}/collect", isNumeric(matcher.group(3)),
                 "non-numeric value for ${matcher.group(2)}: ${matcher.group(3)}")
            note("${name}/collect", declared.containsKey(matcher.group(2)),
                 "emitted undeclared datapoint ${matcher.group(2)}")
        }

        // A discovery/collection key mismatch is the defect that silently produces
        // instances with permanently empty graphs, so it is checked in both directions.
        discovered.each { id ->
            // A module that asks each guest's own node has nothing for a guest on the offline
            // node pve2, and must not pretend otherwise.
            if (name in GUEST_NODE_READ_MODULES && id in onOfflineNode) {
                note("${name}/collect", !perInstance.containsKey(id),
                     "instance ${id} is on offline node pve2 but collected data")
                return
            }
            note("${name}/collect", perInstance.containsKey(id),
                 "instance ${id} was discovered but collected no data")
        }
        perInstance.each { id, keys ->
            note("${name}/collect", discovered.contains(id),
                 "collected data for undiscovered instance ${id}")
            def missing = required - keys
            note("${name}/collect", missing.isEmpty(),
                 "instance ${id} is missing required datapoints ${missing}")
        }
    } else {
        def seen = [] as Set
        lines.each { line ->
            def matcher = PLAIN_LINE.matcher(line)
            if (!matcher.matches()) {
                note("${name}/collect", false, "malformed script line: ${line}")
                return
            }
            seen << matcher.group(1)
            note("${name}/collect", isNumeric(matcher.group(2)),
                 "non-numeric value for ${matcher.group(1)}: ${matcher.group(2)}")
            note("${name}/collect", declared.containsKey(matcher.group(1)),
                 "emitted undeclared datapoint ${matcher.group(1)}")
        }
        def missing = required - seen
        note("${name}/collect", missing.isEmpty(), "missing required datapoints ${missing}")
    }
}

// ------------------------------------- assertions that pin the design decisions

def guestAdFile = new File(scriptsDir, 'Proxmox_VE_Cluster_GuestPerformance.ad.groovy')
def guestAd = runScript(guestAdFile, [hostProps: hostProps])
def guestIds = guestAd.stdout.readLines().findAll { it.trim() }.collect { it.split('##')[0] }

note('templates', !guestIds.contains('qemu-999'),
     "a template was discovered as an instance: ${guestIds}")
note('instance-id', guestIds.contains('qemu-101'),
     "expected the Proxmox-native id qemu-101, got ${guestIds}")
note('instance-id', guestIds.every { !it.contains('pve1') && !it.contains('pve2') },
     "guest instance ids embed a node name and would break on migration: ${guestIds}")

def guestCollect = runScript(new File(scriptsDir, 'Proxmox_VE_Cluster_GuestPerformance.collect.groovy'),
                             [hostProps: hostProps])
def keysFor = { String prefix ->
    guestCollect.stdout.readLines().findAll { it.startsWith(prefix + '.') }
        .collect { it.substring(prefix.length() + 1).split('=')[0] } as Set
}
note('qemu-disk', !keysFor('qemu-101').contains('DiskUsedGB'),
     'DiskUsedGB was emitted for a QEMU guest, which reports no used-disk figure')
note('lxc-disk', keysFor('lxc-200').contains('DiskUsedGB'),
     'DiskUsedGB was not emitted for an LXC guest, which does report one')

// An unreachable API must fail loudly rather than report zeroes, so that LogicMonitor
// keeps existing instances instead of tearing them down during an outage.
def dead = runScript(new File(scriptsDir, 'Proxmox_VE_Cluster_Nodes.collect.groovy'),
                     [hostProps: hostProps + ['pve.api.url': 'http://127.0.0.1:1']])
note('api-down', dead.exit == 2, "expected exit 2 when the API is unreachable, got ${dead.exit}")
note('api-down', dead.stdout.trim().isEmpty(), 'emitted datapoints while the API was unreachable')
note('api-down', dead.stderr.toLowerCase().contains('failed'), 'did not explain the failure on stderr')

// A rejected token must read as a permissions problem, not as an empty cluster.
def badAuth = runScript(guestAdFile, [hostProps: hostProps + ['pve.token.credential': 'wrong@pam!bad=nope']])
note('api-auth', badAuth.exit == 2, "expected exit 2 on HTTP 401, got ${badAuth.exit}")
note('api-auth', badAuth.stderr.contains('token'), 'did not mention the token on stderr')

// Missing configuration must be a clean, explained failure rather than an exception.
def unconfigured = runScript(new File(scriptsDir, 'Proxmox_VE_Cluster_Nodes.collect.groovy'),
                             [hostProps: ['system.hostname': 'pve.example.com']])
note('unconfigured', unconfigured.exit == 2, "expected exit 2 with no token, got ${unconfigured.exit}")
note('unconfigured', unconfigured.stderr.contains('pve.token.credential'),
     'did not name the missing property on stderr')

// ------------------------------------------------------------ PropertySource

def propertySource = new File(scriptsDir, 'Proxmox_VE_Info.groovy')

def detected = runScript(propertySource, [hostProps: hostProps])
def detectedProps = detected.stdout.readLines().findAll { it.trim() }
                        .collectEntries { [(it.split('=', 2)[0]): it.split('=', 2)[1]] }
note('propertysource', detected.exit == 0, "exit was ${detected.exit}, expected 0")
note('info-no-category', !detectedProps.containsKey('system.categories'),
     "Proxmox_VE_Info set a category; categories are set by whoever creates the resource: ${detectedProps}")
note('propertysource', detectedProps['pve.version'] == '8.2.2',
     "did not report the Proxmox version: ${detectedProps}")
note('propertysource', detectedProps['pve.clustered'] == 'true',
     "did not detect cluster membership: ${detectedProps}")
// The value a user copies into the optional pve.topology.cluster. It has to be the name
// the TopologySource keys the cluster on, which is this same row.
note('propertysource', detectedProps['pve.cluster.name'] == 'pve-cluster',
     "did not publish the cluster name: ${detectedProps}")

// A portal is mostly non-Proxmox resources. The PropertySource must stay silent on them
// rather than applying the whole suite to unrelated hosts.
def notProxmox = runScript(propertySource, [hostProps: ['system.hostname': 'some-webserver']])
note('propertysource', notProxmox.exit == 0,
     "exit was ${notProxmox.exit} on a non-Proxmox host, expected 0")
note('propertysource', notProxmox.stdout.trim().isEmpty(),
     "set properties on a host with no Proxmox credentials: ${notProxmox.stdout}")

// ------------------------------------------------- GuestStatus instance ERIs
//
// GuestStatus has its own discovery so that each guest's instance can carry the key the
// TopologySource draws it under. It must discover exactly the shared guest discovery's
// instances, and where it can add no keys its output must be the shared discovery's.

def statusAd = new File(scriptsDir, 'Proxmox_VE_Cluster_GuestStatus.ad.groovy')
def perfAd = new File(scriptsDir, 'Proxmox_VE_Cluster_GuestPerformance.ad.groovy')

// Run a discovery script and return the run plus only the requests it made.
def discoverWith = { File file, Map props ->
    int before = requestLog.size()
    def run = runScript(file, [hostProps: props])
    def log = new ArrayList(requestLog)
    run.requests = log.subList(before, log.size())
    run
}

// id -> [auto: [...], predef: [...]], values URL-decoded: pveDiscover encodes them, and
// the portal reads the decoded form.
def parseAd = { String stdout ->
    def instances = [:]
    stdout.readLines().findAll { it.trim() }.each { line ->
        def matcher = AD_LINE.matcher(line)
        if (!matcher.matches()) { return }
        def props = [auto: [:], predef: [:]]
        matcher.group(4).tokenize('&').each { pair ->
            def parts = pair.split('=', 2)
            def value = URLDecoder.decode(parts.length > 1 ? parts[1] : '', 'UTF-8')
            if (parts[0].startsWith('auto.')) { props.auto[parts[0]] = value }
            else if (parts[0].startsWith('predef.')) { props.predef[parts[0]] = value }
        }
        instances[matcher.group(1)] = props
    }
    instances
}

def extendedFixtures = [
    '/cluster/resources?type=vm': fixtures['/cluster/resources?type=vm'] + [
        [id: 'lxc/103', type: 'lxc', vmid: 103, name: 'relay', node: 'pve1', template: false],
        [id: 'qemu/105', type: 'qemu', vmid: 105, name: 'no-nic', node: 'pve1', template: false],
        // Last on pve1 on purpose: its config read fails, and the per-node breaker skips
        // every later config read on the same node.
        [id: 'qemu/104', type: 'qemu', vmid: 104, name: 'no-config', node: 'pve1', template: false],
    ],
    '/nodes/pve1/lxc/103/config': [hostname: 'relay',
        net0: 'name=eth0,bridge=vmbr0,hwaddr=BC:24:11:0E:00:03,type=veth'],
    '/nodes/pve1/qemu/105/config': [name: 'no-nic', cores: 1],
]

def perfOff = discoverWith(perfAd, hostProps)
note('guest-eri-perf', perfOff.exit == 0 && !perfOff.stdout.trim().isEmpty(),
     "the shared guest discovery failed: ${perfOff.exit} / ${perfOff.stderr}")
def offIds = parseAd(perfOff.stdout).keySet()

def eriOn = withFixtures(extendedFixtures) { discoverWith(statusAd, hostProps) }
def onAd = parseAd(eriOn.stdout)
note('guest-eri-on', eriOn.thrown == null && eriOn.exit == 0,
     "exit was ${eriOn.exit} / ${eriOn.thrown}: ${eriOn.stderr}")
[
    'qemu-101': 'bc:24:11:0e:00:02',
    'lxc-103' : 'bc:24:11:0e:00:03',
    // No NIC: the TopologySource's fallback key, which must match it byte for byte.
    'qemu-105': 'proxmoxve--pve-cluster--qemu-105',
].each { id, key ->
    note('guest-eri-on', onAd[id]?.predef?.get('predef.externalResourceID') == key,
         "${id} externalResourceID was ${onAd[id]?.predef}, expected ${key}")
    note('guest-eri-on', onAd[id]?.predef?.get('predef.externalResourceType') == 'VirtualMachine',
         "${id} externalResourceType was ${onAd[id]?.predef}")
}
// A config that cannot be read, and a guest on an offline node, are still instances.
['qemu-104', 'lxc-200', 'qemu-102'].each { id ->
    note('guest-eri-on', onAd.containsKey(id) && onAd[id].predef.isEmpty(),
         "${id} should be discovered without keys: ${onAd[id]}")
}
note('guest-eri-on', !eriOn.requests.any { it.startsWith('/nodes/pve2/') },
     "asked an offline node for guest configs: ${eriOn.requests}")
note('guest-eri-on', !onAd.containsKey('qemu-999'), 'a template was discovered')
note('guest-eri-on', onAd.keySet().containsAll(offIds),
     "the keys cost instances: ${offIds - onAd.keySet()}")

// Per-node circuit breaker. A node whose first config read fails is assumed sick for the
// rest of the run: every later guest on it is discovered without keys rather than spending
// a pve.api.timeout each. qemu-106 follows qemu-104 on pve1 and also has no config, so only
// qemu-104's read may happen; the guests read before it keep their keys.
def breakerFixtures = extendedFixtures + [
    '/cluster/resources?type=vm': extendedFixtures['/cluster/resources?type=vm'] + [
        [id: 'qemu/106', type: 'qemu', vmid: 106, name: 'after-failure', node: 'pve1', template: false],
    ],
]
def breaker = withFixtures(breakerFixtures) { discoverWith(statusAd, hostProps + ['pve.api.concurrency': '1']) }
def breakerAd = parseAd(breaker.stdout)
def configReads = breaker.requests.findAll { it.endsWith('/config') }
note('guest-eri-breaker', breaker.thrown == null && breaker.exit == 0,
     "exit was ${breaker.exit} / ${breaker.thrown}: ${breaker.stderr}")
note('guest-eri-breaker', configReads.contains('/nodes/pve1/qemu/104/config') &&
                          !configReads.contains('/nodes/pve1/qemu/106/config'),
     "config reads continued on pve1 after its first failure: ${configReads}")
note('guest-eri-breaker', configReads == ['/nodes/pve1/qemu/101/config',
                                          '/nodes/pve1/lxc/103/config',
                                          '/nodes/pve1/qemu/105/config',
                                          '/nodes/pve1/qemu/104/config'],
     "unexpected config reads: ${configReads}")
['qemu-104', 'qemu-106'].each { id ->
    note('guest-eri-breaker', breakerAd.containsKey(id) && breakerAd[id].predef.isEmpty(),
         "${id} should be discovered without keys: ${breakerAd[id]}")
}
['qemu-101', 'lxc-103', 'qemu-105'].each { id ->
    note('guest-eri-breaker', breakerAd[id]?.predef?.get('predef.externalResourceID') as boolean,
         "${id}, read before the failure, lost its key: ${breakerAd[id]}")
}
note('guest-eri-breaker', breaker.stderr.readLines().count { it.contains('pve1') } == 1,
     "expected one stderr line for pve1, got: ${breaker.stderr}")

// A standalone host keys its NIC-less guests under the local node, as the TopologySource does.
def eriStandalone = withFixtures(extendedFixtures +
        ['/cluster/status': [[type: 'node', name: 'pve-a', local: 1, online: 1]]]) {
    discoverWith(statusAd, hostProps)
}
def standaloneKey = parseAd(eriStandalone.stdout)['qemu-105']?.predef?.get('predef.externalResourceID')
note('guest-eri-standalone', standaloneKey == 'proxmoxve--pve-a--qemu-105',
     "standalone fallback key was ${standaloneKey}")

// lm.topo's namespace and blacklist rewrite the TopologySource's keys; the discovery
// keys would no longer match, so the feature steps aside and says why.
['topo.namespace', 'topo.blacklist'].each { prop ->
    def run = withFixtures(extendedFixtures) { discoverWith(statusAd, hostProps + [(prop): 'x']) }
    note('guest-eri-namespace', run.thrown == null && run.exit == 0,
         "${prop}: exit was ${run.exit} / ${run.thrown}")
    note('guest-eri-namespace', parseAd(run.stdout).values().every { it.predef.isEmpty() },
         "${prop} set but keys were still emitted:\n${run.stdout}")
    note('guest-eri-namespace', run.stderr.contains(prop),
         "${prop} set but stderr did not say so: ${run.stderr}")
    note('guest-eri-namespace', !run.requests.any { it.contains('/config') },
         "${prop} set but guest configs were still read: ${run.requests}")
}

// The gating calls are the optional extra; when they fail, discovery is today's.
def gateFails = withFixtures(['/cluster/status': null]) { discoverWith(statusAd, hostProps) }
note('guest-eri-gate-fails', gateFails.thrown == null && gateFails.exit == 0,
     "exit was ${gateFails.exit} / ${gateFails.thrown}")
note('guest-eri-gate-fails', gateFails.stdout == perfOff.stdout,
     "output differs from the shared guest discovery:\n${gateFails.stdout}")

// Only GuestStatus carries the keys, so each guest's key sits on one instance.
def perfOn = withFixtures(extendedFixtures) { discoverWith(perfAd, hostProps) }
note('guest-eri-perf', perfOn.exit == 0 &&
                       parseAd(perfOn.stdout).values().every { it.predef.isEmpty() },
     "GuestPerformance discovery carried instance ERIs:\n${perfOn.stdout}")

// ---------------------------------------------------- Cluster instance ERI
//
// Proxmox_VE_Cluster_Status discovers one instance, the cluster, and on a cluster it carries the key the TopologySource draws the cluster vertex with (DESIGN section 7, spike
// 3 re-run). The wildvalue is fixed, so renaming the cluster orphans nothing.

def clusterAd = new File(scriptsDir, 'Proxmox_VE_Cluster_Status.ad.groovy')
def clusterOff = discoverWith(clusterAd, hostProps)
def clusterOffAd = parseAd(clusterOff.stdout)
note('cluster-eri-default', clusterOff.thrown == null && clusterOff.exit == 0,
     "exit was ${clusterOff.exit} / ${clusterOff.thrown}: ${clusterOff.stderr}")
note('cluster-eri-default', clusterOffAd.keySet() == ['cluster'] as Set,
     "expected exactly the instance 'cluster', got ${clusterOffAd.keySet()}")
note('cluster-eri-default', clusterOff.stdout.startsWith('cluster##pve-cluster##'),
     "the instance is not named after the cluster: ${clusterOff.stdout}")
// On a cluster the key is there with no topology property set.
note('cluster-eri-default', clusterOffAd['cluster']?.predef?.get('predef.externalResourceID') == 'proxmoxve--pve-cluster',
     "no property set but the cluster carried no key: ${clusterOffAd['cluster']}")

def clusterOn = discoverWith(clusterAd, hostProps)
def clusterOnAd = parseAd(clusterOn.stdout)
note('cluster-eri-on', clusterOn.exit == 0 && clusterOnAd.keySet() == ['cluster'] as Set,
     "exit ${clusterOn.exit}, instances ${clusterOnAd.keySet()}: ${clusterOn.stderr}")
// Byte for byte the TopologySource's cluster vertex, pveTopoKey(cluster, null).
note('cluster-eri-on', clusterOnAd['cluster']?.predef?.get('predef.externalResourceID') == 'proxmoxve--pve-cluster',
     "cluster key was ${clusterOnAd['cluster']?.predef}")
// Cluster renders the cluster icon; VirtualCluster renders "??" (DESIGN section 7, spike 3).
note('cluster-eri-on', clusterOnAd['cluster']?.predef?.get('predef.externalResourceType') == 'Cluster',
     "cluster type was ${clusterOnAd['cluster']?.predef}")

// A standalone host has no cluster vertex to carry, but keeps its instance and its data.
def clusterStandalone = withFixtures(['/cluster/status': [[type: 'node', name: 'pve-a', local: 1, online: 1]]]) {
    discoverWith(clusterAd, hostProps)
}
def clusterStandaloneAd = parseAd(clusterStandalone.stdout)
note('cluster-eri-standalone', clusterStandalone.exit == 0 &&
                               clusterStandaloneAd.keySet() == ['cluster'] as Set &&
                               clusterStandaloneAd['cluster'].predef.isEmpty(),
     "standalone discovery was ${clusterStandalone.exit}: ${clusterStandalone.stdout}")
note('cluster-eri-standalone', clusterStandalone.stdout.startsWith('cluster##pve-a##'),
     "a standalone instance is not named after its node: ${clusterStandalone.stdout}")

['topo.namespace', 'topo.blacklist'].each { prop ->
    def run = discoverWith(clusterAd, hostProps + [(prop): 'x'])
    note('cluster-eri-namespace', run.exit == 0 && parseAd(run.stdout)['cluster']?.predef?.isEmpty() == true,
         "${prop} set but the cluster still carried keys: ${run.stdout}")
    note('cluster-eri-namespace', run.stderr.contains(prop), "${prop} set but stderr did not say so")
}

// Discovery that cannot reach the API exits 2, so LogicMonitor keeps the instance.
def clusterDown = withFixtures(['/cluster/status': null]) { discoverWith(clusterAd, hostProps) }
note('cluster-eri-down', clusterDown.exit == 2 && clusterDown.stdout.trim().isEmpty(),
     "API down: exit ${clusterDown.exit}, output ${clusterDown.stdout}")

// --------------------------------------------------------------- topology
//
// Proxmox_VE_Cluster_Topology itself cannot run here. It works through Collector-only snippet
// classes, which is the whole reason build.py emits it outside dist/scripts/. What can
// be tested is the half that is ours: the two preamble helpers that decide a vertex's
// identity. Get either wrong and the map draws vertices that resolve to nothing, which
// looks exactly like a working topology until you click one.

def runSource = { String source, Map bindings ->
    def outBuffer = new ByteArrayOutputStream()
    def errBuffer = new ByteArrayOutputStream()
    def realOut = System.out
    def realErr = System.err
    def result = null
    Throwable thrown = null
    try {
        System.setOut(new PrintStream(outBuffer, true, 'UTF-8'))
        System.setErr(new PrintStream(errBuffer, true, 'UTF-8'))
        result = new GroovyShell(new Binding(bindings)).evaluate(source)
    } catch (Throwable t) {
        thrown = t
    } finally {
        System.setOut(realOut)
        System.setErr(realErr)
    }
    [exit: result, stdout: new String(outBuffer.toByteArray(), 'UTF-8'),
     stderr: new String(errBuffer.toByteArray(), 'UTF-8'), thrown: thrown]
}

def preamble = new File(ROOT, 'scripts/lib/pve_common.groovy').text

// v1's property must not configure v2: a resource carrying only it is unconfigured.
def v1Only = runScript(new File(scriptsDir, 'Proxmox_VE_Cluster_Nodes.collect.groovy'),
    [hostProps: ['pve.api.url': baseUrl, 'pve.api.token.credential': TOKEN]])
note('token-v2', v1Only.exit == 2 && v1Only.stderr.contains('pve.token.credential'),
     "v1's token property configured a v2 module: ${v1Only.exit} / ${v1Only.stderr}")
// A NetScan has netscanProps and no hostProps; the preamble must load there.
def nsProps = runSource(preamble + '\nreturn [pveToken, pveHostProp("pve.api.url")]',
    [netscanProps: ['pve.api.url': baseUrl, 'pve.token.credential': TOKEN]])
note('props-netscan', nsProps.thrown == null && nsProps.exit == [TOKEN, baseUrl],
     "preamble under netscanProps gave ${nsProps.exit} / ${nsProps.thrown}")

// ------------------------------------------------------------- failover (A§3.1)

def failoverProps = hostProps + ['pve.api.url': 'http://127.0.0.1:1',
                                 'pve.api.hosts': '127.0.0.1:1,127.0.0.1:' + server2.address.port]
def fo = runScript(new File(scriptsDir, 'Proxmox_VE_Cluster_Nodes.collect.groovy'), [hostProps: failoverProps])
note('failover', fo.exit == 0 && !fo.stdout.trim().isEmpty(), "did not fail over: ${fo.exit} / ${fo.stderr}")
server2Requests.clear()
def fo401 = runScript(new File(scriptsDir, 'Proxmox_VE_Cluster_Nodes.collect.groovy'),
    [hostProps: failoverProps + ['pve.api.url': baseUrl, 'pve.token.credential': 'bad@pam!x=y']])
note('failover-401', fo401.exit == 2 && fo401.stderr.contains('token') && server2Requests.isEmpty(),
     "a rejected token failed over or did not name the token: ${fo401.stderr} / ${server2Requests}")
def urls = runSource(preamble + '''
return ['10.0.0.5', '10.0.0.5:8443', 'fd00::12', '[fd00::12]', 'pve1.example.com'].collect { pveHostUrl(it) }''',
    [hostProps: ['pve.api.url': 'https://vip.example.com:8006', 'pve.token.credential': TOKEN]])
note('hosturl-ipv6', urls.exit == ['https://10.0.0.5:8006', 'https://10.0.0.5:8443', 'https://[fd00::12]:8006',
                                   'https://[fd00::12]:8006', 'https://pve1.example.com:8006'],
     "pveHostUrl gave ${urls.exit} / ${urls.thrown}")
// M1: a pve.api.hosts entry written as a URL is used as written, less any /api2/json.
def asUrls = runSource(preamble + '''
return ['https://10.0.0.5:8006', 'https://[fd00::12]:8006/', 'http://pve1.example.com:8006/api2/json'].collect { pveHostUrl(it) }''',
    [hostProps: ['pve.api.url': 'https://vip.example.com:8006', 'pve.token.credential': TOKEN]])
note('hosturl-url', asUrls.exit == ['https://10.0.0.5:8006', 'https://[fd00::12]:8006', 'http://pve1.example.com:8006'],
     "pveHostUrl mangled URL entries: ${asUrls.exit} / ${asUrls.thrown}")
// M7: a rejected token names the property that holds it.
note('auth-names-property', fo401.stderr.contains('pve.token.credential'),
     "the 401 message does not name pve.token.credential: ${fo401.stderr.take(300)}")

// ------------------------------------------------------- the cluster role (A§3.1)

def statusWith = { Map pve1, Map pve2, Integer quorate ->
    [[type: 'cluster', name: 'pve-cluster', quorate: quorate, nodes: 2],
     [type: 'node', name: 'pve1', nodeid: 1, online: 1, local: 1, ip: '127.0.0.1'] + pve1,
     [type: 'node', name: 'pve2', nodeid: 2, online: 1, ip: 'fd00::12'] + pve2]
}
def roleOf = { Map props -> runSource(preamble + '\nreturn pveRole()', [hostProps: hostProps + props]).exit }
def nodeProps = ['pve.node': 'pve2', 'system.hostname': '127.0.0.1:' + server.address.port]
withFixtures(['/cluster/status': statusWith([:], [:], 1)]) {
    note('role-active-node', roleOf(nodeProps + ['pve.cluster.role': 'active'])?.collect == true, 'a quorate active node did not collect')
    note('role-standby-quiet', roleOf(nodeProps + ['pve.cluster.role': 'standby', 'pve.cluster.active': 'pve1'])?.collect == false,
         'a standby collected while the active node was online')
}
withFixtures(['/cluster/status': statusWith([online: 0], [:], 1)]) {
    note('role-standby-takeover', roleOf(nodeProps + ['pve.cluster.role': 'standby', 'pve.cluster.active': 'pve1'])?.collect == true,
         'a standby did not take over from an offline active node')
}
// A two-node cluster loses quorum whenever one node is down, so its standby takes over
// without quorum; the active node still stops without it (user, 2026-10-26).
withFixtures(['/cluster/status': statusWith([online: 0], [:], 0)]) {
    note('role-two-node', roleOf(nodeProps + ['pve.cluster.role': 'standby', 'pve.cluster.active': 'pve1'])?.collect == true &&
                          roleOf(nodeProps + ['pve.cluster.role': 'active'])?.collect == true,
         'in a two-node cluster a node without quorum did not collect (the other may simply be down)')
}
// A node whose own corosync is not running has a stale view -- frozen membership, a quorum
// flag that flips between polls (lab, 2026-10-26) -- so it never collects, whatever it says.
withFixtures(['/cluster/status': statusWith([:], [:], 1),
              '/nodes/pve2/services': [[name: 'corosync', 'active-state': 'inactive']]]) {
    note('role-corosync-down', roleOf(nodeProps + ['pve.cluster.role': 'active'])?.collect == false &&
                               roleOf(nodeProps + ['pve.cluster.role': 'standby', 'pve.cluster.active': 'pve1'])?.collect == false,
         'a node whose corosync is down collected on its stale view')
}
withFixtures(['/cluster/status': statusWith([:], [:], 1),
              '/nodes/pve2/services': [[name: 'corosync', 'active-state': 'active']]]) {
    note('role-corosync-up', roleOf(nodeProps + ['pve.cluster.role': 'active'])?.collect == true,
         'a node with corosync running did not collect')
}
// A node's cluster modules read from its own API first: a standby that has taken over must
// report its own view, not the one at pve.api.url, which may be the stale node it replaces.
withFixtures(['/cluster/status': statusWith([online: 0], [:], 1)]) {
    server2Requests.clear()
    def ownFirst = runScript(new File(scriptsDir, 'Proxmox_VE_Cluster_Nodes.collect.groovy'),
        [hostProps: hostProps + nodeProps + ['pve.cluster.role': 'standby', 'pve.cluster.active': 'pve1',
                                             'pve.api.url': 'http://127.0.0.1:' + server2.address.port]])
    note('role-own-api-first', ownFirst.exit == 0 && !ownFirst.stdout.trim().isEmpty() && server2Requests.isEmpty(),
         "a node read through pve.api.url instead of its own API: ${ownFirst.exit} / ${server2Requests}")
}
// Hand-back (user, 2026-10-26; S8): alerts a standby raised while standing in do not clear when
// it goes quiet, so after the active node returns it collects once more -- healthy values clear
// them -- then stops. It remembers that it was collecting between runs, through lm.cache on a
// Collector and a plain map here.
def state = [:]
def roleWithState = { Map props, String label ->
    runSource(preamble + '\nreturn pveRole(label)', [hostProps: hostProps + props, label: label, pveTestState: state]).exit
}
def standbyProps = nodeProps + ['pve.cluster.role': 'standby', 'pve.cluster.active': 'pve1']
def taking = withFixtures(['/cluster/status': statusWith([online: 0], [:], 1)]) { roleWithState(standbyProps, 'Cluster_Status') }
def back = withFixtures(['/cluster/status': statusWith([:], [:], 1)]) { roleWithState(standbyProps, 'Cluster_Status') }
def after = withFixtures(['/cluster/status': statusWith([:], [:], 1)]) { roleWithState(standbyProps, 'Cluster_Status') }
note('role-handback', taking?.collect == true && back?.collect == true && back?.why?.contains('hand-back') && after?.collect == false,
     "hand-back was: taking over ${taking}, back ${back}, after ${after}")
def other = withFixtures(['/cluster/status': statusWith([:], [:], 1)]) { roleWithState(standbyProps, 'Cluster_Backups') }
note('role-handback-per-module', other?.collect == false, "a module that never stood in handed back: ${other}")
// Every cluster collect script names itself to the role check, so each hands back on its own.
scriptsDir.listFiles().findAll { it.name ==~ /Proxmox_VE_Cluster_.*\.collect\.groovy/ }.each { f ->
    note('role-handback-label', f.text.contains("pveRole('" + f.name.replace('.collect.groovy', '') + "')"),
         "${f.name} does not name itself to pveRole")
}

// Three nodes or more: a standby without quorum waits, since the majority side may still collect.
def threeNodes = { Map pve1, Integer quorate -> statusWith(pve1, [:], quorate) + [[type: 'node', name: 'pve3', nodeid: 3, online: 1, ip: '10.0.0.3']] }
withFixtures(['/cluster/status': threeNodes([online: 0], 0)]) {
    note('role-minority', roleOf(nodeProps + ['pve.cluster.role': 'standby', 'pve.cluster.active': 'pve1'])?.collect == false &&
                          roleOf(nodeProps + ['pve.cluster.role': 'active'])?.collect == false,
         'a node without quorum in a 3-node cluster collected')
}
withFixtures(['/cluster/status': threeNodes([online: 0], 1)]) {
    note('role-three-node-takeover', roleOf(nodeProps + ['pve.cluster.role': 'standby', 'pve.cluster.active': 'pve1'])?.collect == true,
         'a quorate standby in a 3-node cluster did not take over')
}
note('role-address-down', roleOf(['pve.cluster.role': 'standby', 'pve.cluster.active': 'address:127.0.0.1:1'])?.collect == true,
     'a standby behind an unreachable address did not take over')
note('role-address-up', roleOf(['pve.cluster.role': 'standby', 'pve.cluster.active': 'address:127.0.0.1:' + server.address.port])?.collect == false,
     'a standby collected while its cluster address answered')
withFixtures(['/cluster/status': [[type: 'node', name: 'pve-a', nodeid: 1, online: 1, local: 1]]]) {
    note('role-standalone', roleOf(['pve.cluster.role': 'active', 'pve.node': 'pve-a',
                                    'system.hostname': '127.0.0.1:' + server.address.port])?.collect == true,
         'a standalone host did not collect')
}
// Every cluster collect script is silent, and exits 0, where it should not collect.
scriptsDir.listFiles().findAll { it.name ==~ /Proxmox_VE_Cluster_.*\.collect\.groovy/ }.sort { it.name }.each { f ->
    def silent = runScript(f, [hostProps: hostProps + ['pve.cluster.role': 'none']])
    note('role-silent', silent.exit == 0 && silent.stdout.trim().isEmpty(),
         "${f.name} printed or failed with role none: ${silent.exit} / ${silent.stdout.take(80)}")
}
// --------------------------------------------------- node scope (A§3.2)

def nodeRun = discoverWith(new File(scriptsDir, 'Proxmox_VE_Node_Disks.ad.groovy'), nodeHostProps)
note('node-scope', nodeRun.exit == 0 && !nodeRun.requests.any { it.startsWith('/cluster/resources') } &&
                   nodeRun.requests.every { !it.startsWith('/nodes/') || it.startsWith('/nodes/pve1/') },
     "a node module looked beyond its own node: ${nodeRun.exit} / ${nodeRun.requests}")
def selfFromStatus = runSource(preamble + '\nreturn pveSelfNode()', [hostProps: nodeHostProps.findAll { it.key != 'pve.node' }])
note('node-self', selfFromStatus.exit == 'pve1', "pveSelfNode from the local row gave ${selfFromStatus.exit} / ${selfFromStatus.thrown}")
def noFailover = runScript(new File(scriptsDir, 'Proxmox_VE_Node_Disks.ad.groovy'),
    [hostProps: nodeHostProps + ['system.hostname': '127.0.0.1:1', 'pve.api.hosts': '127.0.0.1:' + server2.address.port]])
note('node-no-failover', noFailover.exit == 2, "a node module failed over to another node: ${noFailover.exit}")

// ---------------------------------- per-guest loops (A§3.9 rules 2-5)

// Rule 2: a guest's read goes to the node that owns it, when that node's address is one the
// NetScan listed in pve.api.hosts.
def ownerPort = '127.0.0.1:' + server2.address.port
def owner = withFixtures(['/cluster/status': statusWith([ip: ownerPort], [:], 1)]) {
    server2Requests.clear()
    discoverWith(statusAd, hostProps + ['pve.api.hosts': ownerPort])
}
note('guestloop-owner', owner.exit == 0 && server2Requests.contains('/nodes/pve1/qemu/101/config') &&
                        !owner.requests.contains('/nodes/pve1/qemu/101/config'),
     "guest configs were not read from the owning node: ${server2Requests} / ${owner.requests}")
def v6 = runSource(preamble + '\nreturn pveNodeBases(rows)', [hostProps: hostProps, rows: fixtures['/cluster/status']])
note('guestloop-ipv6', v6.exit?.pve2 == 'http://[fd00::12]:' + server.address.port, "owner URLs were ${v6.exit} / ${v6.thrown}")
def budget = runScript(statusAd, [hostProps: hostProps + ['pve.api.budget.ms': '0']])
note('guestloop-budget', budget.exit == 0 && budget.stderr.contains('time budget reached') && budget.stdout.contains('qemu-101##'),
     "no budget stop, or guests lost from discovery: ${budget.exit} / ${budget.stderr}")
// An agent that is not running answers HTTP 500 for its own guest; with httpErrorTrips false
// that must not stop the node's other reads.
def agentRun = withFixtures([:]) {
    int before = requestLog.size()
    def run = runSource(preamble + '''
def seen = []
pveForEachGuest('Agent reads', guests, rows, '/agent/network-get-interfaces', { g, data, error -> seen << [g.vmid, error != null] }, false)
return seen''', [hostProps: hostProps, guests: fixtures['/cluster/resources?type=vm'].findAll { it.node == 'pve1' && !it.template },
                rows: fixtures['/cluster/status']])
    run.requests = new ArrayList(requestLog).subList(before, requestLog.size())
    run
}
note('guestloop-http-error', agentRun.thrown == null && agentRun.exit?.every { it[1] } &&
                             agentRun.requests.count { it.endsWith('/agent/network-get-interfaces') } == agentRun.exit?.size(),
     "an HTTP error stopped the loop: ${agentRun.exit} / ${agentRun.thrown} / ${agentRun.requests}")

// ------------------------------------------------ cluster health score (A§3.2)

def hs = { rows, stalls -> runSource(preamble + '\nreturn pveHealthScore(rows, stalls)',
    [hostProps: hostProps, rows: rows, stalls: stalls]).exit }
def twoNodes = statusWith([:], [:], 1)
note('health', hs(twoNodes, [pve1: 10d, pve2: 30d]) == [NodesOnline: 2d, NodesTotal: 2d, NodesOnlinePercent: 100d, HealthScore: 80d],
     "two healthy nodes gave ${hs(twoNodes, [pve1: 10d, pve2: 30d])}")
note('health-offline', hs(statusWith([:], [online: 0], 1), [pve1: 0d])?.HealthScore == 25d,
     "an offline node did not count twice (ratio and mean): ${hs(statusWith([:], [online: 0], 1), [pve1: 0d])}")
note('health-quorum', hs(statusWith([:], [:], 0), [pve1: 0d, pve2: 0d])?.HealthScore == 0d, 'no quorum did not score 0')
note('health-fallback', hs(twoNodes, [:]) != null && !hs(twoNodes, [:]).containsKey('HealthScore'),
     "a score was fabricated with no node input: ${hs(twoNodes, [:])}")
note('health-standalone', hs([[type: 'node', name: 'pve-a', online: 1, local: 1]], ['pve-a': 0d])?.HealthScore == 100d,
     'a standalone host was not counted quorate')
def statusRun = runScript(new File(scriptsDir, 'Proxmox_VE_Cluster_Status.collect.groovy'), [hostProps: hostProps])
def sv = statusRun.stdout.readLines().findAll { it ==~ /^[A-Za-z0-9_]+=.*/ }.collectEntries { def kv = it.split('=', 2); [(kv[0]): kv[1] as double] }
// The fixture: pve1 online, worst pressure CPU 2.5, pve2 offline -> 100 x 1/2 x mean(1 - 0.025, 0) = 24.38
note('health-collect', sv.NodesOnlinePercent == 50d && sv.HealthScore == 24.38d, "Cluster_Status printed ${sv.subMap(['NodesOnlinePercent', 'HealthScore'])}")

// ---------------------------------------- Cluster_Nodes keeps only Status (A§3.2)

def clusterNodesRun = runScript(new File(scriptsDir, 'Proxmox_VE_Cluster_Nodes.collect.groovy'), [hostProps: hostProps])
def clusterNodeKeys = clusterNodesRun.stdout.readLines().findAll { it.contains('=') }
    .collect { BATCH_LINE.matcher(it) }.findAll { it.matches() }.collect { it.group(2) } as Set
note('cluster-nodes-status-only', clusterNodesRun.exit == 0 && clusterNodeKeys == ['Status'] as Set,
     "Cluster_Nodes printed ${clusterNodeKeys}; CPU, memory and uptime belong to Node_Detail")

// ------------------------------------------- Node_Detail on its node (A§3.2)

def detail = runScript(new File(scriptsDir, 'Proxmox_VE_Node_Detail.collect.groovy'), [hostProps: nodeHostProps])
def dv = detail.stdout.readLines().findAll { it.contains('=') }.collectEntries { def kv = it.split('=', 2); [(kv[0]): kv[1] as double] }
note('detail-pressure', dv.PressureCPUSomePercent == 2.5d && dv.PressureIOSomePercent == 0.37d, "pressure was ${dv} / ${detail.stderr}")
note('detail-quorum', dv.Quorate == 1d && dv.NodesOnline == 1d, "quorum view was ${dv}")
note('detail-cpu', dv.CPUUsagePercent == 15.23d && dv.CPUCount == 16d && dv.MemoryUsagePercent == 50d && dv.UpTimeSeconds == 604800d,
     "CPU, memory and uptime were ${dv}")
def noPsi = withFixtures(['/nodes/pve1/rrddata?timeframe=hour&cf=AVERAGE': [[time: 1, cpu: 0.1, loadavg: 0.2, maxcpu: 4]]]) {
    runScript(new File(scriptsDir, 'Proxmox_VE_Node_Detail.collect.groovy'), [hostProps: nodeHostProps])
}
note('detail-no-pressure', noPsi.exit == 0 && !noPsi.stdout.contains('Pressure') && noPsi.stdout.contains('CPUUsagePercent'),
     "pressure fabricated, or the rest lost: ${noPsi.exit} / ${noPsi.stdout}")
def standaloneDetail = withFixtures(['/cluster/status': [[type: 'node', name: 'pve1', nodeid: 1, online: 1, local: 1]]]) {
    runScript(new File(scriptsDir, 'Proxmox_VE_Node_Detail.collect.groovy'), [hostProps: nodeHostProps])
}
note('detail-standalone', standaloneDetail.exit == 0 && !standaloneDetail.stdout.contains('Quorate'),
     "a standalone host reported a quorum it does not have: ${standaloneDetail.stdout}")
def stall = runSource(preamble + '''
return [pveNodeStall([pressurecpusome: 2.5, pressureiosome: 30, pressurememorysome: 1], [:]),
        pveNodeStall([cpu: 0.1], [loadavg: ['6.0'], cpuinfo: [cpus: 4]]),
        pveNodeStall([cpu: 0.1], [loadavg: ['1.0'], cpuinfo: [cpus: 4]]),
        pveNodeStall([:], [:]),
        pveLatestRrd([[time: 1, cpu: 0.1], [time: 2, cpu: 0.2], [time: 3, cpu: null]])?.time]''', [hostProps: hostProps])
note('stall', stall.exit == [30d, 100d, 25d, null, 2], "pveNodeStall / pveLatestRrd gave ${stall.exit} / ${stall.thrown}")

// Only the collecting resource stamps instance ERIs: a standby's would double every key.
withFixtures(['/cluster/status': statusWith([:], [:], 1)]) {
    def standbyAd = runScript(new File(scriptsDir, 'Proxmox_VE_Cluster_GuestStatus.ad.groovy'),
        [hostProps: hostProps + nodeProps + ['pve.cluster.role': 'standby', 'pve.cluster.active': 'pve1']])
    note('role-eri', standbyAd.exit == 0 && standbyAd.stdout.trim() && !standbyAd.stdout.contains('predef.'),
         "a standby's discovery stamped ERIs or found nothing: ${standbyAd.exit} / ${standbyAd.stdout.take(120)}")
}
def helpers = runSource(preamble + """
return [
    cluster : pveTopoKey('pve-cluster', null),
    node    : pveTopoKey('PVE Cluster', 'pve1.example.com'),
    qemu    : pveGuestMac(['net0': 'virtio=BC:24:11:0E:00:02,bridge=vmbr0,firewall=1']),
    lxc     : pveGuestMac(['net0': 'name=eth0,bridge=vmbr0,hwaddr=BC:24:11:0A:1B:2C,type=veth']),
    ordered : pveGuestMac(['net1': 'virtio=AA:BB:CC:DD:EE:02', 'net0': 'virtio=AA:BB:CC:DD:EE:01']),
    noNic   : pveGuestMac(['name': 'nothing-here', 'cores': 2]),
    nothing : pveGuestMac(null),
]
""", [hostProps: hostProps])

note('topo-helpers', helpers.thrown == null, "preamble helpers threw ${helpers.thrown}")
def helper = helpers.exit ?: [:]

note('topo-helpers', helper.cluster == 'proxmoxve--pve-cluster',
     "cluster vertex key was ${helper.cluster}")
// Folded to the ERI-safe alphabet: a node is routinely an FQDN and a cluster name can
// carry spaces, and neither may reach a key that addERI_Proxmox_VE_Node has to reproduce.
note('topo-helpers', helper.node == 'proxmoxve--pve-cluster--pve1-example-com',
     "node vertex key was ${helper.node}")

// QEMU writes the MAC after the model, LXC after hwaddr. Both must yield the same
// lowercase form LogicMonitor keys a resource's ERI on.
note('topo-helpers', helper.qemu == 'bc:24:11:0e:00:02',
     "QEMU guest MAC was ${helper.qemu}")
note('topo-helpers', helper.lxc == 'bc:24:11:0a:1b:2c',
     "LXC guest MAC was ${helper.lxc}")
// net0 wins over net1 whatever order the map iterates in; a guest that changed which
// adapter it was identified by would change identity and move on the map.
note('topo-helpers', helper.ordered == 'aa:bb:cc:dd:ee:01',
     "did not take the lowest-numbered adapter: ${helper.ordered}")
// No NIC is a permanent fact and must read as absent, not as a wrong MAC.
note('topo-helpers', helper.noNic == null,
     "invented a MAC for a guest with no adapter: ${helper.noNic}")
note('topo-helpers', helper.nothing == null,
     "invented a MAC from a null config: ${helper.nothing}")

// A node resource without the API token names itself from its properties. The key it
// arrives at must be the one the TopologySource builds from /cluster/status for the same
// node, or that node is not drawn -- and nothing reports the mismatch.
def identityOf = { Map props ->
    def run = runSource(preamble + '\nreturn pveTopoNodeIdentity()', [hostProps: props])
    note('topo-identity', run.thrown == null, "pveTopoNodeIdentity threw ${run.thrown}")
    run.exit
}
def keyOf = { Map props ->
    def run = runSource(preamble + '''
def id = pveTopoNodeIdentity()
return id ? pveTopoKey(id.cluster, id.node) : null''', [hostProps: props])
    run.exit
}
def clusterProp = ['pve.topology.cluster': 'pve-cluster']

note('topo-identity', keyOf(clusterProp + ['system.hostname': 'pve2.example.com']) ==
                      'proxmoxve--pve-cluster--pve2',
     'an FQDN hostname did not yield the key the TopologySource builds for that node')
note('topo-identity', identityOf(clusterProp + ['system.hostname': 'pve2']) ==
                      [cluster: 'pve-cluster', node: 'pve2'],
     'a short hostname was not taken as the node name')
// sysname is what the host calls itself; hostname is whatever the resource was added as.
note('topo-identity', identityOf(clusterProp + ['system.sysname': 'pve3',
                                                'system.hostname': 'proxmox-c.lan'])?.node == 'pve3',
     'system.hostname was preferred over system.sysname')
note('topo-identity', identityOf(clusterProp + ['system.sysname': 'pve3',
                                                'system.hostname': '10.0.0.13'])?.node == 'pve3',
     'an IP hostname displaced a usable sysname')
note('topo-identity', identityOf(clusterProp + ['pve.topology.node': 'pve4',
                                                'system.sysname': 'pve3'])?.node == 'pve4',
     'pve.topology.node did not override the derived name')
// The NetScan writes pve.node; it names the node even on a resource added by its address.
note('topo-identity', identityOf(clusterProp + ['pve.node': 'pve7', 'system.hostname': '10.0.0.7'])?.node == 'pve7',
     'pve.node did not name the node')
// An address names nothing. Guessing would stamp a key that matches no vertex.
note('topo-identity', identityOf(clusterProp + ['system.hostname': '10.0.0.12']) == null,
     'an IPv4 address was taken as a node name')
note('topo-identity', identityOf(clusterProp + ['system.hostname': 'fd00::12']) == null,
     'an IPv6 address was taken as a node name')
// No cluster name, no key: the TopologySource keys every node under the cluster's name.
note('topo-identity', identityOf(['system.hostname': 'pve2']) == null,
     'produced an identity with no pve.topology.cluster')

// The second node key needs no cluster name: the node's name plus an address it holds.
// The TopologySource takes both from /cluster/status; addERI_Proxmox_VE_Node takes the name
// from the resource and the address from system.ips, which LogicMonitor writes with IPv6
// uncompressed. The two must meet byte for byte, or a token-less node is not drawn.
def ipKey = runSource(preamble + """
def rows = statusRows.findAll { it.type == 'node' }
def sysIps = [pve1: 'fe80:0:0:0:be24:11ff:fe00:1,192.168.10.11',
              pve2: 'fe80:0:0:0:be24:11ff:fe00:1, fd00:0:0:0:0:0:0:12']
return [
    ip       : ['192.0.2.211', ' 192.0.2.211 ', 'fd00::12', 'FD00:0:0:0:0:0:0:12',
                'fd00::12%vmbr0'].collectEntries { [(it): pveTopoIp(it)] },
    rejected : [null, '', '127.0.0.1', '::1', 'fe80::1', 'fe80:0:0:0:be24:11ff:fe00:1',
                '0.0.0.0', '999.1.1.1', 'pve1.example.com',
                // The container's own name resolves offline, through /etc/hosts, to a usable
                // address: only the literal-only gate keeps it out, so this pins that no name
                // is ever looked up.
                InetAddress.localHost.hostName].collectEntries { [(it): pveTopoIp(it)] },
    v4       : pveTopoNodeKey('pve1', '192.168.10.11'),
    fqdn     : pveTopoNodeKey('PVE1.example.com', '192.168.10.11'),
    v6       : pveTopoNodeKey('pve2', 'fd00::12'),
    missing  : [pveTopoNodeKey('pve2', null), pveTopoNodeKey(null, '192.168.10.11'),
                pveTopoNodeKey('', '192.168.10.11'), pveTopoNodeKey('pve2', 'fe80::1')],
    trips    : rows.collect { [it.name, pveTopoNodeKey(it.name, it.ip),
                               pveTopoNodeKeys(it.name, sysIps[it.name].tokenize(','))] },
    dedupe   : pveTopoNodeKeys('pve1', ['192.168.10.11', '192.168.10.11', '10.0.0.5']),
    vertex   : [pveTopoNodeVertexKey('pve-cluster', 'pve1', '192.168.10.11'),
                pveTopoNodeVertexKey('pve-cluster', 'pve2', null),
                pveTopoNodeVertexKey('pve-cluster', 'pve2', 'fe80::1')],
]
""", [hostProps: hostProps, statusRows: fixtures['/cluster/status']])
note('topo-ipkey', ipKey.thrown == null, "IP key helpers threw ${ipKey.thrown}")
def ik = ipKey.exit ?: [:]
note('topo-ipkey', ik.ip == ['192.0.2.211': '192.0.2.211', ' 192.0.2.211 ': '192.0.2.211',
                             'fd00::12': 'fd00:0:0:0:0:0:0:12',
                             'FD00:0:0:0:0:0:0:12': 'fd00:0:0:0:0:0:0:12',
                             'fd00::12%vmbr0': 'fd00:0:0:0:0:0:0:12'],
     "addresses did not normalise: ${ik.ip}")
// Loopback, link-local and wildcard name no reachable node; a hostname or a malformed
// quad must not reach InetAddress, which would resolve it through DNS.
note('topo-ipkey', ik.rejected && ik.rejected.values().every { it == null },
     "accepted an address that names no node: ${ik.rejected}")
note('topo-ipkey', ik.v4 == 'proxmoxve--node--pve1--192-168-10-11', "IPv4 node key was ${ik.v4}")
note('topo-ipkey', ik.fqdn == ik.v4, "an FQDN node name was not shortened: ${ik.fqdn}")
note('topo-ipkey', ik.v6 == 'proxmoxve--node--pve2--fd00-0-0-0-0-0-0-12', "IPv6 node key was ${ik.v6}")
note('topo-ipkey', ik.missing == [null, null, null, null], "keyed a missing part: ${ik.missing}")
note('topo-ipkey', ik.trips?.size() == 2 && ik.trips.every { name, vertex, eri ->
                       vertex != null && eri == [vertex] },
     "the vertex key and the ERI key built from system.ips differ: ${ik.trips}")
// One key per node vertex: lm.topo joins a vertex's keys into one ID ('a_b'), and the
// portal then matches only a resource carrying all of them, not any one (2026-10-21, a
// token-less node carrying only the IP key was left unmatched). The IP key when the
// /cluster/status row has a usable address, else the cluster-scoped key.
note('topo-ipkey', ik.vertex == ['proxmoxve--node--pve1--192-168-10-11',
                                 'proxmoxve--pve-cluster--pve2', 'proxmoxve--pve-cluster--pve2'],
     "node vertex keys were ${ik.vertex}")
note('topo-ipkey', ik.dedupe == ['proxmoxve--node--pve1--192-168-10-11',
                                 'proxmoxve--node--pve1--10-0-0-5'],
     "node keys were not deduplicated in order: ${ik.dedupe}")

// The node's own name, which the IP key needs, without the cluster name.
def nodeNameOf = { Map props ->
    def run = runSource(preamble + '\nreturn pveTopoNodeName()', [hostProps: props])
    note('topo-ipkey', run.thrown == null, "pveTopoNodeName threw ${run.thrown}")
    run.exit
}
note('topo-ipkey', nodeNameOf(['system.hostname': 'pve2.example.com']) == 'pve2',
     'a hostname without pve.topology.cluster did not name the node')
note('topo-ipkey', nodeNameOf(['system.hostname': '10.0.0.12']) == null,
     'an IP address was taken as a node name')
note('topo-ipkey', nodeNameOf(['pve.topology.node': 'pve4', 'system.sysname': 'pve3']) == 'pve4',
     'pve.topology.node did not override the derived name')
// An address there is the pvecm status mistake (DESIGN section 7, 2026-10-22): it would key the
// node as "192", so it is ignored and the derived name used instead.
note('topo-ipkey', nodeNameOf(['pve.topology.node': '192.0.2.211', 'system.sysname': 'Node-B']) == 'Node-B',
     'an IPv4 address in pve.topology.node was taken as the node name')
note('topo-ipkey', nodeNameOf(['pve.topology.node': 'fd00::12', 'system.sysname': 'Node-B']) == 'Node-B',
     'an IPv6 address in pve.topology.node was taken as the node name')

// The guest MAC prefix test and the cluster-name rule are shared by the TopologySource,
// the guest ERI PropertySource and the guest discovery, none of which can run here.
def guestMac = runSource(preamble + """
return [
    lower   : pveGuestMacMatch('bc:24:11:0e:00:01', null),
    upper   : pveGuestMacMatch('BC:24:11:0E:00:04', null),
    pi      : pveGuestMacMatch('88:a2:9e:0e:00:09', null),
    custom  : pveGuestMacMatch('02:00:00:12:34:56', ' BC:24:11 , 02:00:00'),
    notDflt : pveGuestMacMatch('bc:24:11:0e:00:01', '02:00:00'),
    blank   : pveGuestMacMatch('  ', null),
    none    : pveGuestMacMatch(null, null),
    cluster : pveTopoClusterName([[type: 'cluster', name: 'pve-cluster'], [type: 'node', name: 'pve1', local: 1]]),
    single  : pveTopoClusterName([[type: 'node', name: 'pve-a', local: 1]]),
    empty   : pveTopoClusterName([]),
]
""", [hostProps: hostProps])
note('guest-mac', guestMac.thrown == null, "guest MAC helpers threw ${guestMac.thrown}")
def gm = guestMac.exit ?: [:]
note('guest-mac', gm.lower == 'bc:24:11:0e:00:01', "lower MAC was ${gm.lower}")
note('guest-mac', gm.upper == 'bc:24:11:0e:00:04', "upper MAC was ${gm.upper}")
note('guest-mac', gm.pi == null, "a non-Proxmox MAC matched: ${gm.pi}")
note('guest-mac', gm.custom == '02:00:00:12:34:56', "custom prefixes did not match: ${gm.custom}")
note('guest-mac', gm.notDflt == null, "an override did not replace the default prefix: ${gm.notDflt}")
note('guest-mac', gm.blank == null, "a blank MAC matched: ${gm.blank}")
note('guest-mac', gm.none == null, "a null MAC matched: ${gm.none}")
note('guest-mac', gm.cluster == 'pve-cluster', "cluster name was ${gm.cluster}")
note('guest-mac', gm.single == 'pve-a', "standalone cluster name was ${gm.single}")
note('guest-mac', gm.empty == 'standalone', "empty status cluster name was ${gm.empty}")
// The fallback key must be built from the same cluster name in both places.
note('guest-mac', new File(ROOT, 'scripts/Proxmox_VE_Cluster_Topology.topo.groovy').text.contains('pveTopoClusterName('),
     'the TopologySource derives its cluster name without pveTopoClusterName')

def discoverRaw = runSource(preamble + "pveDiscover('qemu/1', 'n', 'd', [a: 'x'], " +
    "['predef.externalResourceID': 'bc:24:11:00:00:01'])", [hostProps: hostProps])
note('discover-raw', discoverRaw.thrown == null && discoverRaw.stdout.trim() ==
     'qemu-1##n##d####auto.a=x&predef.externalResourceID=bc%3A24%3A11%3A00%3A00%3A01',
     "raw properties output was ${discoverRaw.stdout.trim()} / ${discoverRaw.thrown}")

// The ERI PropertySource is the other half of the node vertex, and it cannot run here
// either: LogicMonitor's own addERI_* modules build the ERI output through lm.topo's
// emitEri and printEriArray rather than printing JSON, so this script imports the same
// Collector-only classes the TopologySource does.
//
// What is left to assert is structural, and it is the thing that actually breaks. Both
// halves must derive the node key from pveTopoKey. If either ever inlines its own
// version, they can drift apart and every node vertex stops matching its resource --
// with nothing failing anywhere, because each half is individually correct.
def eriScript = new File(ROOT, 'scripts/addERI_Proxmox_VE_Node.groovy').text
def topoScript = new File(ROOT, 'scripts/Proxmox_VE_Cluster_Topology.topo.groovy').text

note('addERI', eriScript.contains('pveTopoKey('),
     'the ERI PropertySource builds its node key without pveTopoKey')
note('addERI', topoScript.contains('pveTopoKey('),
     'the TopologySource builds its node key without pveTopoKey')
// The same for the key that needs no cluster name, which is what draws a token-less node.
note('addERI', eriScript.contains('pveTopoNodeKeys('),
     'the ERI PropertySource builds its name-plus-IP keys without pveTopoNodeKeys')
note('addERI', topoScript.contains('pveTopoNodeVertexKey('),
     'the TopologySource builds its node vertex key without pveTopoNodeVertexKey')
note('addERI', !(topoScript =~ /eri\(\[pveTopoKey\([^)]*\),\s*pveTopoNode/),
     'a node vertex carries two keys again; lm.topo joins them and the portal then matches neither alone')
note('addERI', !eriScript.contains('rawERIs'),
     'the ERI PropertySource hand-writes the rawERIs JSON instead of using emitEri')
note('addERI', eriScript.contains('lmtopo.emitEri(') &&
               eriScript.contains('lmtopo.printEriArray('),
     'the ERI PropertySource does not use the documented emitEri/printEriArray pair')

// Silence is the contract Proxmox_VE_Info also keeps: most resources in a portal
// are not Proxmox, and a PropertySource that prints on one it does not understand
// corrupts that resource's properties. Asserted by reading the guard, since the script
// cannot be executed here.
note('addERI', eriScript.contains('if (!name) {') && eriScript.contains('if (!keys) {') &&
               eriScript.contains('return 0'),
     'the ERI PropertySource does not return silently when it cannot name the node')
// The point of the property path: a node with no token still gets its key. If the script
// ever returns on pveConfigError again, only the token-holding node can be drawn.
note('addERI', eriScript.contains('pveTopoNodeIdentity()') &&
               !eriScript.contains('if (pveConfigError) {'),
     'the ERI PropertySource requires the API token again')

// The guest-labelling ERI PropertySource is Collector-only for the same reason, so its
// matching logic lives in pveGuestMacMatch (asserted above) and what is asserted here is
// the wiring. Priority 3 is the finding of spec spike 1: the resource type comes from
// the highest-priority ERI across categories, and the built-in net.l2 entry is 2, so
// anything lower leaves a guest drawn as PhysicalServer with nothing failing anywhere.
def guestEriScript = new File(ROOT, 'scripts/addERI_Proxmox_VE_GuestResource.groovy').text
def guestEriDefn = new JsonSlurper().parse(new File(ROOT, 'modules/addERI_Proxmox_VE_GuestResource.json'))

note('addERI-guest', guestEriScript.contains('pveGuestEriMac('),
     'the guest ERI PropertySource does not take its MAC from pveGuestEriMac')
// A guest the NetScan created carries its Proxmox MAC as pve.guest.mac; that wins over
// whatever MAC in-guest monitoring reported, and needs no prefix test.
def eriMac = { Map props -> runSource(preamble + '\nreturn pveGuestEriMac()', [hostProps: props]).exit }
note('guest-eri-mac', eriMac(['pve.guest.mac': 'BC:24:11:AA:BB:CC', 'auto.network.mac_address': 'bc:24:11:00:00:01']) == 'bc:24:11:aa:bb:cc' &&
                      eriMac(['pve.guest.mac': '52:54:00:12:34:56']) == '52:54:00:12:34:56' &&
                      eriMac(['auto.network.mac_address': 'bc:24:11:00:00:01']) == 'bc:24:11:00:00:01' &&
                      eriMac(['auto.network.mac_address': '00:50:56:00:00:01']) == null,
     'pveGuestEriMac did not prefer pve.guest.mac, or matched a non-Proxmox MAC')
// A guest the NetScan created is only pinged, so it has no net.l2 MAC ERI: unless this module
// stamps the bare MAC the topology's guest vertex is keyed by, the edge stops at the
// GuestStatus instance and the resource is drawn alone (lab map, 2026-10-26).
def eriKeys = runSource(preamble + '\nreturn pveGuestEriKeys("bc:24:11:00:01:04")', [hostProps: [:]]).exit
note('guest-eri-bare-mac', eriKeys == ['bc:24:11:00:01:04', 'proxmoxve--vm--bc:24:11:00:01:04'] &&
                           guestEriScript.contains('pveGuestEriKeys(mac)'),
     "the guest ERI keys were ${eriKeys}: the topology vertex's bare MAC is missing")
note('addERI-guest', guestEriScript.contains("lmtopo.emitEri('virt.proxmoxve', 3,") &&
                     guestEriScript.contains("'VirtualMachineResource'") &&
                     guestEriScript.contains('lmtopo.printEriArray('),
     'the guest ERI PropertySource does not emit virt.proxmoxve at priority 3 as VirtualMachineResource')
note('addERI-guest', !guestEriScript.contains('rawERIs'),
     'the guest ERI PropertySource hand-writes the rawERIs JSON instead of using emitEri')
// A guest has no token, so the preamble always sets pveConfigError: returning on it would
// make the module a no-op on every resource it applies to.
note('addERI-guest', !guestEriScript.contains('if (pveConfigError) {'),
     'the guest ERI PropertySource returns on pveConfigError, which every guest sets')
note('addERI-guest', preamble.contains("pveHostProp('auto.network.mac_address')") &&
                     preamble.contains("pveHostProp('pve.guest.mac_prefix')"),
     'pveGuestEriMac does not read the MAC and the prefix override')
note('addERI-guest', guestEriDefn.appliesTo ==
         'pve.guest.mac || ((auto.network.mac_address =~ "^[bB][cC]:24:11" || pve.guest.mac_prefix) && ' +
         '(isLinux() || isWindows()))',
     "the guest ERI PropertySource AppliesTo is ${guestEriDefn.appliesTo}")
// Always on: labelling a guest correctly is not something an end user should opt into.
note('addERI-guest', !guestEriDefn.containsKey('optional'),
     'the guest ERI PropertySource is marked optional')

// Never optional: an ERI module cannot be turned off cleanly, because keys it stamped stay in
// predef.externalResourceID after it stops applying, and the portal refuses to delete them
// (DESIGN section 7). addERI_Proxmox_VE_Node finds a node by the token, by pve.topology.node, or
// by a Proxmox kernel in system.sysinfo; an LXC guest reports its host's kernel, so the
// guest MAC prefix and its override keep containers out.
def topoDefn = new JsonSlurper().parse(new File(ROOT, 'modules/Proxmox_VE_Cluster_Topology.json'))
def eriDefn = new JsonSlurper().parse(new File(ROOT, 'modules/addERI_Proxmox_VE_Node.json'))
[topoDefn, eriDefn].each { defn ->
    note('topology-never-optional', !defn.containsKey('optional'),
         "${defn.name} is marked optional")
}
note('topology-applies-to', topoDefn.appliesTo == 'pve.cluster.role == "active" || pve.cluster.role == "standby"',
     "the TopologySource AppliesTo is ${topoDefn.appliesTo}")
note('topology-applies-to', eriDefn.appliesTo == 'hasCategory("ProxmoxVE_Node")',
     "the node ERI PropertySource AppliesTo is ${eriDefn.appliesTo}")

// ------------------------------------------------------------------- Backups
//
// One instance per guest per job, grouped by job, plus one per guest no job covers. Last
// backups and failures come from vzdump run logs, read newest first until every job guest
// has a success, and matched to a job by the run's --storage.

def backupsAd = new File(scriptsDir, 'Proxmox_VE_Cluster_Backups.ad.groovy')
def backupsCollect = new File(scriptsDir, 'Proxmox_VE_Cluster_Backups.collect.groovy')
def backupLog = { int n -> String.format('/nodes/pve1/tasks/UPID:pve1:0000%04X:00F00D00:6AD0%04X:vzdump::root@pam:/log?start=0&limit=1000', n, n) }
def parseBatch = { String stdout ->
    def data = [:].withDefault { [:] }
    stdout.readLines().each { line ->
        def matcher = BATCH_LINE.matcher(line)
        if (matcher.matches()) { data[matcher.group(1)][matcher.group(2)] = matcher.group(3) as double }
    }
    data
}

def backupAdRun = discoverWith(backupsAd, hostProps)
def backupInstances = parseAd(backupAdRun.stdout)
note('backups-ad', backupAdRun.exit == 0 && backupInstances.keySet() ==
         ['backup-4e5f5bfc-094c-101', 'backup-4e5f5bfc-094c-103', 'backup-weekly-101',
          'backup-weekly-105', 'none-102', 'none-200'] as Set,
     "instances were ${backupInstances.keySet()}: ${backupAdRun.stderr}")
// Grouped by the job's comment, else its id; uncovered guests in their own group.
['backup-4e5f5bfc-094c-101': 'backup-4e5f5bfc-094c',
 'backup-weekly-105'       : 'Weekly to PBS',
 'none-102'                : 'No backup job'].each { id, group ->
    note('backups-ad', backupInstances[id]?.auto?.get('auto.pve_backup_job') == group,
         "${id} group was ${backupInstances[id]?.auto}")
}
// The guest's volumes, named: included ones by key and volume, excluded ones with the reason.
note('backups-ad', backupInstances['backup-4e5f5bfc-094c-103']?.auto?.get('auto.pve_backup_included_volumes') ==
                       'rootfs: local-lvm:vm-103-disk-0' &&
                   backupInstances['backup-4e5f5bfc-094c-103']?.auto?.get('auto.pve_backup_excluded_volumes') ==
                       'mp0: /mnt/data (not a volume)' &&
                   !backupInstances['backup-4e5f5bfc-094c-101']?.auto?.containsKey('auto.pve_backup_excluded_volumes'),
     "volume properties were ${backupInstances['backup-4e5f5bfc-094c-103']?.auto} / ${backupInstances['backup-4e5f5bfc-094c-101']?.auto}")
note('backups-ad', backupAdRun.stdout.contains('backup-4e5f5bfc-094c-101##web-01 (101)##'),
     'the display name is not "<guest> (<vmid>)"')
// Proxmox types a job's guests qemu / lxc (the lab's shape); its schema's VM / CT is accepted too.
note('backups-ad', backupInstances['backup-4e5f5bfc-094c-103']?.auto?.get('auto.pve_type') == 'lxc' &&
                   backupInstances['backup-weekly-105']?.auto?.get('auto.pve_type') == 'qemu',
     "guest types were ${backupInstances.collectEntries { k, v -> [(k): v.auto['auto.pve_type']] }}")

def backupRun = discoverWith(backupsCollect, hostProps)
def backupData = parseBatch(backupRun.stdout)
def nowHours = System.currentTimeMillis() / 3600000.0d
def ageNear = { Double hours, long start -> hours != null && Math.abs(hours - (nowHours - start / 3600.0d)) < 0.1 }
note('backups-collect', backupRun.exit == 0, "exit was ${backupRun.exit}: ${backupRun.stderr}")

// Last good backup from the daily run before the failed one; the failed run is newer.
def daily101 = backupData['backup-4e5f5bfc-094c-101']
note('backups-collect', daily101.LastBackupSizeGB == 2.15 && daily101.JobEnabled == 1 &&
                        daily101.FailedSinceLastBackup == 1 && daily101.NotCovered == 0 &&
                        daily101.IncludedVolumes == 1 && daily101.ExcludedVolumes == 0 &&
                        daily101.HoursUntilNextRun > 0 && ageNear(daily101.LastBackupAgeHours, 1792803602L),
     "daily 101 was ${daily101}")
// Succeeded inside the run that failed for 101: that run is its last good backup.
def daily103 = backupData['backup-4e5f5bfc-094c-103']
note('backups-collect', daily103.LastBackupSizeGB == 1.61 && daily103.FailedSinceLastBackup == 0 &&
                        daily103.IncludedVolumes == 1 && daily103.ExcludedVolumes == 1 &&
                        ageNear(daily103.LastBackupAgeHours, 1792865236L),
     "daily 103 was ${daily103}")
// The same guest in the disabled weekly job: its PBS run logged no size, and the daily
// failure (another storage) is not this job's.
def weekly101 = backupData['backup-weekly-101']
note('backups-collect', weekly101.JobEnabled == 0 && weekly101.FailedSinceLastBackup == 0 &&
                        !weekly101.containsKey('LastBackupSizeGB') && !weekly101.containsKey('HoursUntilNextRun') &&
                        ageNear(weekly101.LastBackupAgeHours, 1792461600L),
     "weekly 101 was ${weekly101}")
// A failure older than the guest's last good backup is over.
def weekly105 = backupData['backup-weekly-105']
note('backups-collect', weekly105.FailedSinceLastBackup == 0 && ageNear(weekly105.LastBackupAgeHours, 1792461600L),
     "weekly 105 was ${weekly105}")
note('backups-collect', backupData['none-102'] == [NotCovered: 1.0d],
     "an uncovered guest reported ${backupData['none-102']}")

// Newest first, each log read whole, and no further once every job guest has a success:
// the oldest run's log is never requested. No storage listing, no offline node.
def logReads = backupRun.requests.findAll { it.contains('/log') }
note('backups-calls', logReads == [backupLog(1), backupLog(2), backupLog(5), backupLog(3)],
     "unexpected log reads: ${logReads}")
note('backups-calls', !backupRun.requests.any { it.contains('/content') || it.startsWith('/nodes/pve2/') },
     "listed storage or asked an offline node: ${backupRun.requests}")

// A node whose runs cannot be read leaves the last-backup datapoints empty, not guessed.
def backupNoRuns = withFixtures(['/nodes/pve1/tasks?typefilter=vzdump&limit=50': null]) {
    discoverWith(backupsCollect, hostProps)
}
def unread = parseBatch(backupNoRuns.stdout)['backup-4e5f5bfc-094c-101']
note('backups-collect', backupNoRuns.exit == 0 && unread.JobEnabled == 1 &&
                        !unread.containsKey('LastBackupAgeHours') && !unread.containsKey('FailedSinceLastBackup'),
     "unread runs reported ${unread}")

// So does a run whose log cannot be read: collection still succeeds.
def backupNoLog = withFixtures([(backupLog(2)): null]) { discoverWith(backupsCollect, hostProps) }
def unreadLog = parseBatch(backupNoLog.stdout)['backup-4e5f5bfc-094c-101']
note('backups-collect', backupNoLog.exit == 0 && unreadLog.JobEnabled == 1 &&
                        !unreadLog.containsKey('LastBackupAgeHours') && backupNoLog.stderr.contains('not read'),
     "an unread log reported ${unreadLog}: ${backupNoLog.stderr}")

// Without not-backed-up (older Proxmox) the job instances are still discovered.
def backupNoInfo = withFixtures(['/cluster/backup-info/not-backed-up': null]) {
    discoverWith(backupsAd, hostProps)
}
note('backups-ad', backupNoInfo.exit == 0 && parseAd(backupNoInfo.stdout).keySet().every { !it.startsWith('none-') } &&
                   parseAd(backupNoInfo.stdout).size() == 4 && backupNoInfo.stderr.contains('not listed'),
     "without not-backed-up: ${backupNoInfo.exit} ${backupNoInfo.stdout} ${backupNoInfo.stderr}")

// No job list means no instances can be trusted: exit 2 keeps the existing ones.
def backupNoJobs = withFixtures(['/cluster/backup': null]) { discoverWith(backupsAd, hostProps) }
note('backups-ad', backupNoJobs.exit == 2, "discovery without /cluster/backup exited ${backupNoJobs.exit}")
def backupDead = runScript(backupsCollect, [hostProps: hostProps + ['pve.api.url': 'http://127.0.0.1:1']])
note('backups-collect', backupDead.exit == 2, "collection with the API down exited ${backupDead.exit}")

// ---------------------------------------------------------------- Sizes in names
//
// Storage and OSD instances carry their size in the display name, guests and nodes their root
// disk in the description, so a percentage graph reads against the size it is a share of.
def adRows = { String module ->
    discoverWith(new File(scriptsDir, module + '.ad.groovy'), hostProps).stdout.readLines()
        .collect { AD_LINE.matcher(it) }.findAll { it.matches() }
        .collect { [name: it.group(2), description: it.group(3)] }
}
def storageNames = adRows('Proxmox_VE_Cluster_StorageCapacity')*.name
note('sizes', storageNames.contains('local on pve1 (107 GB)') && storageNames.contains('ceph-vm on pve1 (11.0 TB)') &&
              storageNames.contains('backup.nfs on pve2'),
     "storage names were ${storageNames}")
def osdNames = adRows('Proxmox_VE_Cluster_CephOSD')*.name
// An OSD whose tree entry reports no size keeps its bare name.
note('sizes', osdNames.count { it ==~ /osd\.\d+ \(4\.0 TB\)/ } == 2 && osdNames.contains('osd.2'), "OSD names were ${osdNames}")
def guestDescriptions = adRows('Proxmox_VE_Cluster_GuestStatus')*.description
note('sizes', guestDescriptions.any { it.endsWith(', root disk 54 GB') } && guestDescriptions.any { it.endsWith(', root disk 8.6 GB') },
     "guest descriptions were ${guestDescriptions}")
def nodeDescriptions = adRows('Proxmox_VE_Cluster_Nodes')*.description
note('sizes', nodeDescriptions.count { it.endsWith('pve1, root filesystem 101 GB') } == 1 &&
              nodeDescriptions.any { it.endsWith('pve2') },
     "node descriptions were ${nodeDescriptions}")

// ---------------------------------------------------------------- Migrations
//
// Running migrations from /cluster/tasks; finished ones from each online node's vzmigrate and
// qmigrate history over the last hour. Times are relative to now, so they are built here.

def clusterCollect = new File(scriptsDir, 'Proxmox_VE_Cluster_Status.collect.groovy')
long nowSec = System.currentTimeMillis().intdiv(1000L)
def migTask = { String type, String id, long ago, String status ->
    def task = [upid: "UPID:pve1:00000000:00000000:00000000:${type}:${id}:root@pam:".toString(), node: 'pve1',
                type: type, id: id, user: 'root@pam', starttime: nowSec - ago]
    if (status) { task.endtime = nowSec - ago + 60; task.status = status }
    task
}
def parsePlain = { String stdout ->
    stdout.readLines().collectEntries { line ->
        def matcher = PLAIN_LINE.matcher(line)
        matcher.matches() ? [(matcher.group(1)): matcher.group(2) as double] : [:]
    }
}
def migrationFixtures = [
    '/cluster/tasks': [migTask('vzmigrate', '108', 30, null), migTask('vzdump', '', 30, null),
                       migTask('qmigrate', '101', 200, 'OK')],
    '/nodes/pve1/tasks?typefilter=vzmigrate&limit=100': [migTask('vzmigrate', '108', 600, 'OK'),
                                                        migTask('vzmigrate', '109', 1200, 'migration aborted'),
                                                        migTask('vzmigrate', '110', 7200, 'migration aborted')],
    '/nodes/pve1/tasks?typefilter=qmigrate&limit=100': [migTask('qmigrate', '101', 300, 'OK')],
]
def migRun = withFixtures(migrationFixtures) { discoverWith(clusterCollect, hostProps) }
def migData = parsePlain(migRun.stdout)
// One running (no end time); two finished OK and one failed in the hour; one failure older.
note('migrations', migRun.exit == 0 && migData.MigrationsRunning == 1 &&
                   migData.MigrationsCompletedLastHour == 2 && migData.MigrationsFailedLastHour == 1,
     "migration counts were ${migData.subMap(['MigrationsRunning', 'MigrationsCompletedLastHour', 'MigrationsFailedLastHour'])}: ${migRun.stderr}")
note('migrations', migRun.stderr.contains('guest 109 from pve1 (migration aborted)') && !migRun.stderr.contains('guest 110'),
     "the failed guest was not named, or an old failure was: ${migRun.stderr}")
note('migrations', !migRun.requests.any { it.startsWith('/nodes/pve2/') },
     "asked an offline node: ${migRun.requests}")
// A node whose history cannot be read withholds the hourly counts; running is still reported.
def migPartial = withFixtures(migrationFixtures + ['/nodes/pve1/tasks?typefilter=qmigrate&limit=100': null]) {
    discoverWith(clusterCollect, hostProps)
}
def partialData = parsePlain(migPartial.stdout)
note('migrations', migPartial.exit == 0 && partialData.MigrationsRunning == 1 &&
                   !partialData.containsKey('MigrationsCompletedLastHour') && !partialData.containsKey('MigrationsFailedLastHour'),
     "an unread history reported ${partialData}")

// Guest Status: Migrating follows Proxmox's migrate lock, and only that lock.
def statusCollect = new File(scriptsDir, 'Proxmox_VE_Cluster_GuestStatus.collect.groovy')
def migratingVms = fixtures['/cluster/resources?type=vm'].collect { guest ->
    guest.vmid == 101 ? guest + [lock: 'migrate'] : guest
}
def migratingRun = withFixtures(['/cluster/resources?type=vm': migratingVms]) { discoverWith(statusCollect, hostProps) }
def migrating = parseBatch(migratingRun.stdout)
note('migrations', migrating['qemu-101']?.Migrating == 1 && migrating['qemu-101']?.Locked == 1 &&
                   migrating['qemu-102']?.Migrating == 0 && migrating['qemu-102']?.Locked == 1,
     "Migrating was 101 ${migrating['qemu-101']} / 102 (backup lock) ${migrating['qemu-102']}")

// The entire justification for the BatchScript design.
note('call-efficiency', requested.contains('/cluster/resources?type=vm'),
     'guest collection did not use the bulk endpoint')
// Guest metrics come from the bulk endpoint; a node's rrddata is read only for its pressure
// figures, which nothing else reports.
note('call-efficiency', !requested.any { it.contains('rrddata') && (it.contains('/qemu/') || it.contains('/lxc/')) },
     "guest collection used the per-guest rrddata endpoint: ${requested}")

// ---------------------------------------------------------------- Snapshots
//
// One instance per guest. Proxmox's "current" entry is never counted, ages are relative to
// now, RAM snapshots are VM-only, and one unreadable list stops that node's reads this run.

def snapCollect = new File(scriptsDir, 'Proxmox_VE_Cluster_Snapshots.collect.groovy')
long snapNow = System.currentTimeMillis().intdiv(1000L)
def snapCurrent = [description: 'You are here!', digest: 'abc', name: 'current', running: 1]
def snapAt = { String name, double daysAgo, Integer vmstate = null ->
    def entry = [description: '', name: name, snaptime: snapNow - (long) (daysAgo * 86400)]
    if (vmstate != null) { entry.vmstate = vmstate }
    entry
}
def snapGuests = fixtures['/cluster/resources?type=vm'] + [
    [id: 'lxc/103', type: 'lxc', vmid: 103, node: 'pve1', name: 'ct-snap', status: 'running', template: 0],
    [id: 'lxc/104', type: 'lxc', vmid: 104, node: 'pve1', name: 'ct-none', status: 'running', template: 0]]
def snapFixtures = [
    '/cluster/resources?type=vm'  : snapGuests,
    '/nodes/pve1/qemu/101/snapshot': [snapAt('newer', 2.0, 1), snapAt('older', 10.0, 1), snapAt('disk-only', 5.0, 0), snapCurrent],
    '/nodes/pve1/lxc/103/snapshot' : [snapAt('a', 3.0), snapCurrent, snapAt('b', 1.0)],
    '/nodes/pve1/lxc/104/snapshot' : [snapCurrent],
]
def snapRun = withFixtures(snapFixtures) { discoverWith(snapCollect, hostProps) }
def snap = parseBatch(snapRun.stdout)
note('snapshots', snapRun.exit == 0 && snap['qemu-101'].SnapshotCount == 3 && snap['qemu-101'].SnapshotsWithRAM == 2 &&
                  Math.abs(snap['qemu-101'].OldestSnapshotAgeDays - 10.0) < 0.02,
     "qemu-101 was ${snap['qemu-101']}: ${snapRun.stderr}")
note('snapshots', snap['lxc-103'].SnapshotCount == 2 && Math.abs(snap['lxc-103'].OldestSnapshotAgeDays - 3.0) < 0.02 &&
                  !snap['lxc-103'].containsKey('SnapshotsWithRAM'),
     "lxc-103 was ${snap['lxc-103']}")
// Only "current": a count of 0 and no age, never an age of 0.
note('snapshots', snap['lxc-104'] == [SnapshotCount: 0.0d], "lxc-104 was ${snap['lxc-104']}")
note('snapshots', !snapRun.requests.any { it.startsWith('/nodes/pve2/') || it.contains('/999/') },
     "asked an offline node's guest or a template: ${snapRun.requests}")
// One failure stops that node's reads: 101 fails first, so 103 and 104 are skipped.
def snapBroken = withFixtures(snapFixtures + ['/nodes/pve1/qemu/101/snapshot': null]) { discoverWith(snapCollect, hostProps + ['pve.api.concurrency': '1']) }
note('snapshots', snapBroken.exit == 0 && parseBatch(snapBroken.stdout).isEmpty() &&
                  !snapBroken.requests.any { it.contains('/lxc/10') } &&
                  snapBroken.stderr.readLines().count { it.contains('Snapshot reads stopped for node pve1') } == 1,
     "after a failure: ${snapBroken.stdout} / ${snapBroken.requests} / ${snapBroken.stderr}")
def snapDead = withFixtures(['/cluster/resources?type=vm': null]) { discoverWith(snapCollect, hostProps) }
note('snapshots', snapDead.exit == 2, "an unreadable guest list exited ${snapDead.exit}")

// ------------------------------------------- the NetScan plan (N§4, §5.1, §5.2)
//
// pveNetscanPlan is pure: it turns what the NetScan read into the resources it emits. The
// Collector-only body (lm.api, lm.emit) is a thin layer over it.

def netscanLibFile = new File(ROOT, 'scripts/lib/pve_netscan.groovy')
def netscanLib = netscanLibFile.exists() ? netscanLibFile.text : ''
def plan = { Map input ->
    def run = runSource(preamble + '\n' + netscanLib + '\nreturn pveNetscanPlan(input)', [netscanProps: input.props, input: input])
    if (run.thrown) { note('ns-runs', false, "pveNetscanPlan threw ${run.thrown}") }
    run.exit ?: [resources: [], report: []]
}
def nsBase = [status: fixtures['/cluster/status'], resources: [],
              reach: [pve1: [api: true, ping: true], pve2: [api: false, ping: false, why: 'offline']],
              configs: [:], addresses: [:], existing: [],
              props: ['pve.api.url': 'https://192.168.10.11:8006', 'pve.token.credential': TOKEN]]
def p1 = plan(nsBase)
def byHost = p1.resources.collectEntries { [(it.hostname): it] }
note('ns-noaddress', p1.resources.size() == 2 && !p1.resources.any { it.hostProps['system.categories'] == 'ProxmoxVE_Cluster' },
     "a cluster resource was made although the API address is a node: ${p1.resources*.hostname}")
note('ns-pair-offline', byHost['192.168.10.11']?.hostProps?.get('pve.cluster.role') == 'active' &&
                        byHost['fd00::12']?.hostProps?.get('pve.cluster.role') == 'standby' &&
                        byHost['fd00::12']?.hostProps?.get('pve.cluster.active') == 'pve1' &&
                        byHost['192.168.10.11']?.hostProps?.get('pve.cluster.active') == 'none',
     "role pair was ${p1.resources*.hostProps}")
note('ns-reach', byHost['fd00::12']?.hostProps?.get('pve.netscan.reachable') == 'true' &&
                 byHost['fd00::12']?.hostProps?.get('pve.netscan.status') == 'offline' &&
                 byHost['192.168.10.11']?.hostProps?.get('pve.netscan.status') == 'ok' &&
                 byHost['192.168.10.11']?.hostProps?.get('pve.netscan.reachable') == 'true',
     "reachability was ${p1.resources*.hostProps}")
note('ns-node-props', byHost['192.168.10.11']?.displayname == 'pve1' &&
                      byHost['192.168.10.11']?.hostProps?.subMap(['system.categories', 'pve.node', 'pve.cluster', 'pve.serial', 'pve.api.hosts', 'pve.token.credential']) ==
                      ['system.categories': 'ProxmoxVE_Node', 'pve.node': 'pve1', 'pve.cluster': 'pve-cluster',
                       'pve.serial': 'pve-cluster::node::pve1', 'pve.api.hosts': '192.168.10.11,fd00::12', 'pve.token.credential': TOKEN],
     "node properties were ${byHost['192.168.10.11']?.hostProps}")
note('ns-nodename', plan(nsBase + [props: nsBase.props + ['pve.netscan.node.hostname': 'name']]).resources*.hostname.sort() == ['pve1', 'pve2'],
     'pve.netscan.node.hostname=name did not use node names')
note('ns-groups', p1.resources*.groupName.flatten().unique() == ['Proxmox VE/Cluster - pve-cluster/Nodes'], "groups ${p1.resources*.groupName}")
note('ns-explicit', p1.resources.every { r -> ['pve.netscan.status', 'pve.cluster.role', 'pve.cluster.active', 'pve.netscan.reachable'].every { r.hostProps[it] } },
     'a property was left out instead of set explicitly')
def p2 = plan(nsBase + [props: nsBase.props + ['pve.api.url': 'https://pve-vip.example.com:8006']])
def cl = p2.resources.find { it.hostname == 'pve-vip.example.com' }
note('ns-address', cl?.hostProps?.get('pve.cluster.role') == 'active' && cl?.groupName == ['Proxmox VE/Cluster - pve-cluster'] &&
     cl?.hostProps?.get('system.categories') == 'ProxmoxVE_Cluster' && cl?.displayname == 'pve-cluster' &&
     p2.resources.find { it.hostname == '192.168.10.11' }?.hostProps?.get('pve.cluster.active') == 'address:pve-vip.example.com' &&
     p2.resources.find { it.hostname == 'fd00::12' }?.hostProps?.get('pve.cluster.role') == 'none',
     "address case was ${p2.resources*.hostname} / ${p2.resources*.hostProps}")
def p2b = plan(nsBase + [props: nsBase.props + ['pve.netscan.cluster.address': 'pve-vip.example.com']])
note('ns-address-prop', p2b.resources.any { it.hostname == 'pve-vip.example.com' }, 'pve.netscan.cluster.address was not used')
def p3 = plan(nsBase + [status: [[type: 'node', name: 'pve-a', nodeid: 1, online: 1, local: 1, ip: '10.0.0.9']],
                        reach: ['pve-a': [api: true, ping: true]], props: nsBase.props + ['pve.api.url': 'https://10.0.0.9:8006']])
note('ns-standalone', p3.resources.size() == 1 && p3.resources[0].hostProps['system.categories'] == 'ProxmoxVE_Cluster,ProxmoxVE_Node' &&
     p3.resources[0].groupName == ['Proxmox VE/Host - pve-a'] && p3.resources[0].hostProps['pve.cluster.role'] == 'active',
     "standalone was ${p3.resources}")
def p4 = plan(nsBase + [props: nsBase.props + ['pve.netscan.nodes': 'false', 'pve.api.url': 'https://pve-vip.example.com:8006']])
note('ns-nodes-off', p4.resources*.hostname.sort() == ['192.168.10.11', 'pve-vip.example.com'],
     "pve.netscan.nodes=false kept ${p4.resources*.hostname}")
note('ns-report', p1.report.any { it.contains('no cluster address') && it.contains('active pve1') && it.contains('standby pve2') },
     "the run did not say which case it found: ${p1.report}")

// --------------------------------------------- the NetScan plan: guests (N§5.3)

def guestAddr = { Map guest, Map config, Object live ->
    def run = runSource(preamble + '\n' + netscanLib + '\nreturn pveGuestAddress(guest, config, live)',
        [netscanProps: [:], guest: guest, config: config, live: live])
    run.thrown ? "threw ${run.thrown}" : run.exit
}
// The lab's nvr01 container (S6): Docker bridges beside the real interface.
note('ns-addr-lxc', guestAddr([type: 'lxc', status: 'running'],
        [net0: 'name=eth0,bridge=vmbr0,gw=192.0.2.1,hwaddr=BC:24:11:0E:00:03,ip=192.0.2.60/24,type=veth'],
        [[name: 'lo', hwaddr: '00:00:00:00:00:00', inet: '127.0.0.1/8'],
         [name: 'docker0', hwaddr: '82:47:92:0e:00:0a', inet: '172.17.0.1/16'],
         [name: 'eth0', hwaddr: 'bc:24:11:0e:00:03', inet: '192.0.2.60/24']]) == [ip: '192.0.2.60', source: 'live'],
     'the container address was not the one on net0\'s interface')
// The lab's automation01 VM: Tailscale first, the LAN address after a link-local one.
note('ns-addr-agent', guestAddr([type: 'qemu', status: 'running'], [net0: 'virtio=DC:A6:32:0E:00:08,bridge=vmbr0'],
        [result: [[name: 'lo', 'hardware-address': '00:00:00:00:00:00', 'ip-addresses': [['ip-address': '127.0.0.1', 'ip-address-type': 'ipv4']]],
                  [name: 'tailscale0', 'hardware-address': '00:00:00:00:00:00', 'ip-addresses': [['ip-address': '100.64.0.15', 'ip-address-type': 'ipv4']]],
                  [name: 'enp0s18', 'hardware-address': 'dc:a6:32:0e:00:08', 'ip-addresses': [['ip-address': 'fe80::a:b:c:d', 'ip-address-type': 'ipv6'],
                                                                                               ['ip-address': '192.0.2.44', 'ip-address-type': 'ipv4']]]]]) ==
     [ip: '192.0.2.44', source: 'live'], 'the VM address was not the IPv4 on net0\'s interface')
note('ns-addr-none', guestAddr([type: 'lxc', status: 'running'], [net0: 'name=eth0,bridge=vmbr0,hwaddr=BC:24:11:0E:00:07,type=veth'],
        [[name: 'lo', hwaddr: '00:00:00:00:00:00', inet: '127.0.0.1/8']]) == null,
     'a DHCP container with no address got one')
note('ns-addr-static', guestAddr([type: 'lxc', status: 'stopped'], [net0: 'name=eth0,hwaddr=BC:24:11:AA:00:01,ip=192.0.2.200/24,type=veth'], null) ==
     [ip: '192.0.2.200', source: 'config'], 'a stopped container did not fall back to its static address')
note('ns-addr-dhcp', guestAddr([type: 'qemu', status: 'stopped'], [net0: 'virtio=DC:A6:32:00:00:01', ipconfig0: 'ip=dhcp'], null) == null,
     'ip=dhcp was taken as an address')

def nsGuests = [
    [id: 'qemu/101', type: 'qemu', vmid: 101, name: 'web', node: 'pve1', status: 'running', pool: 'prod', tags: 'a;b'],
    [id: 'lxc/102', type: 'lxc', vmid: 102, name: 'web', node: 'pve1', status: 'running'],
    [id: 'lxc/103', type: 'lxc', vmid: 103, name: 'dhcp-ct', node: 'pve1', status: 'running'],
    [id: 'qemu/999', type: 'qemu', vmid: 999, name: 'tmpl', node: 'pve1', status: 'stopped', template: 1],
]
def nsGuestBase = nsBase + [resources: nsGuests,
    configs: [101: [net0: 'virtio=BC:24:11:00:01:01,bridge=vmbr0'], 102: [net0: 'name=eth0,hwaddr=BC:24:11:00:01:02,type=veth'],
              103: [net0: 'name=eth0,hwaddr=BC:24:11:00:01:03,type=veth']],
    addresses: [101: [ip: '192.0.2.101', source: 'live'], 102: [ip: '192.0.2.102', source: 'config']],
    props: nsBase.props + ['pve.netscan.guests': 'true']]
def noCreds = plan(nsGuestBase + [existing: null])
note('ns-guests-nocreds', !noCreds.resources.any { it.hostProps['system.categories'] == 'ProxmoxVE_Guest' } &&
                          noCreds.report.any { it.contains('no LogicMonitor API credentials') },
     "guests were created without LM API credentials, or silently: ${noCreds.report}")
def withGuests = plan(nsGuestBase)
def gByHost = withGuests.resources.findAll { it.hostProps['system.categories'] == 'ProxmoxVE_Guest' }.collectEntries { [(it.hostname): it] }
note('ns-guests', gByHost.keySet() == ['192.0.2.101', '192.0.2.102'] as Set &&
                  withGuests.report.any { it.contains('103') && it.contains('no address') },
     "guests were ${gByHost.keySet()} / ${withGuests.report}")
note('ns-guest-names', gByHost['192.0.2.101']?.displayname == 'web (101)' && gByHost['192.0.2.102']?.displayname == 'web (102)',
     "duplicate names were ${gByHost.values()*.displayname}")
note('ns-guest-props', gByHost['192.0.2.101']?.hostProps?.subMap(['pve.vmid', 'pve.guest.type', 'pve.pool', 'pve.tags', 'pve.guest.mac', 'pve.serial', 'pve.node', 'netscan.powerstate']) ==
         ['pve.vmid': '101', 'pve.guest.type': 'qemu', 'pve.pool': 'prod', 'pve.tags': 'a;b', 'pve.guest.mac': 'bc:24:11:00:01:01',
          'pve.serial': 'pve-cluster::101', 'pve.node': 'pve1', 'netscan.powerstate': 'running'] &&
     gByHost['192.0.2.102']?.hostProps?.get('pve.pool') == 'none' && gByHost['192.0.2.102']?.hostProps?.get('pve.tags') == 'none' &&
     !gByHost['192.0.2.101']?.hostProps?.containsKey('pve.token.credential') &&
     gByHost['192.0.2.101']?.groupName == ['Proxmox VE/Cluster - pve-cluster/Guests'],
     "guest properties were ${gByHost['192.0.2.101']?.hostProps} / ${gByHost['192.0.2.102']?.hostProps}")
def unaddr = plan(nsGuestBase + [props: nsGuestBase.props + ['pve.netscan.guests.unaddressed': 'true']])
note('ns-guests-unaddressed', unaddr.resources.any { it.hostname == 'dhcp-ct' }, 'unaddressed=true did not create the guest by name')
def byGuestName = plan(nsGuestBase + [props: nsGuestBase.props + ['pve.netscan.guest.hostname': 'name']])
note('ns-guest-hostname', byGuestName.resources.findAll { it.hostProps['system.categories'] == 'ProxmoxVE_Guest' }*.hostname.sort() == ['dhcp-ct', 'web', 'web'],
     "guest.hostname=name gave ${byGuestName.resources*.hostname}")
def filtered = plan(nsGuestBase + [props: nsGuestBase.props + ['pve.netscan.guests.filter.vmid': '100-101', 'pve.netscan.guests.filter.type': 'qemu']])
note('ns-guest-filter', filtered.resources.findAll { it.hostProps['system.categories'] == 'ProxmoxVE_Guest' }*.hostname == ['192.0.2.101'],
     "the filters let through ${filtered.resources*.hostname}")
def offGuests = plan(nsGuestBase + [props: nsGuestBase.props + ['pve.netscan.guests': 'false']])
note('ns-guests-off', !offGuests.resources.any { it.hostProps['system.categories'] == 'ProxmoxVE_Guest' }, 'guests were created with guests off')

// ------------------------------- the NetScan plan: deduplication (N§6, §7)

// A guest the NetScan made before, now at a new address: one resource, kept at its old
// hostname (a new one would make LogicMonitor create a second resource, S5), and the change
// handed to the body to apply through the LM API.
def moved = plan(nsGuestBase + [existing: [[id: 77, hostname: '192.0.2.70', displayName: 'Media01', props: ['pve.serial': 'pve-cluster::101']]],
                                addresses: nsGuestBase.addresses + [101: [ip: '192.0.2.75', source: 'live']]])
def movedGuest = moved.resources.find { it.hostProps['pve.serial'] == 'pve-cluster::101' }
note('ns-dedup-moved', moved.resources.count { it.hostProps['pve.serial'] == 'pve-cluster::101' } == 1 &&
                       movedGuest?.hostname == '192.0.2.70' &&
                       movedGuest?.hostProps?.get('pve.netscan.status') == 'address changed: was 192.0.2.70, now 192.0.2.75' &&
                       moved.hostnameUpdates == [[id: 77, hostname: '192.0.2.75']],
     "a moved guest was ${movedGuest} / updates ${moved.hostnameUpdates}")
// A node the portal already monitors, not made by the NetScan: skipped and reported by default.
def preexisting = [[id: 5, hostname: '192.168.10.11', displayName: 'Node-A', props: [:]]]
def clash = plan(nsBase + [existing: preexisting])
note('ns-dedup-skip', !clash.resources.any { it.hostname == '192.168.10.11' } &&
                      clash.report.any { it.contains('collision') && it.contains('Node-A') },
     "a node already monitored was ${clash.resources*.hostname} / ${clash.report}")
def adopt = plan(nsBase + [existing: preexisting, props: nsBase.props + ['hostname.source': 'lm']])
note('ns-dedup-adopt', adopt.resources.find { it.hostname == '192.168.10.11' }?.displayname == 'Node-A',
     "hostname.source=lm did not adopt: ${adopt.resources*.displayname}")
def second = plan(nsBase + [existing: preexisting, props: nsBase.props + ['hostname.source': 'netscan']])
note('ns-dedup-netscan', second.resources.find { it.hostname == '192.168.10.11' }?.displayname == 'pve1 - 192.168.10.11',
     "hostname.source=netscan did not rename: ${second.resources*.displayname}")
// Addresses are compared, not strings: LogicMonitor writes IPv6 uncompressed.
def v6clash = plan(nsBase + [existing: [[id: 6, hostname: 'FD00:0:0:0:0:0:0:12', displayName: 'b', props: [:]]]])
note('ns-dedup-ipv6', !v6clash.resources.any { it.hostname == 'fd00::12' }, 'an IPv6 duplicate written differently was not caught')
def skipped = plan(nsBase + [existing: preexisting, props: nsBase.props + ['skip.device.dedupe': 'true']])
note('ns-dedup-off', skipped.resources.any { it.hostname == '192.168.10.11' && it.displayname == 'pve1' }, 'skip.device.dedupe did not turn matching off')
// A guest gone from Proxmox: reported for an operator, never deleted.
def orphaned = plan(nsBase + [existing: [[id: 9, hostname: '192.0.2.250', displayName: 'old', props: ['pve.cluster': 'pve-cluster', 'pve.serial': 'pve-cluster::555']]]])
note('ns-orphans', orphaned.orphans*.id == [9] && orphaned.report.any { it.contains('orphan') && it.contains('old') },
     "orphans were ${orphaned.orphans} / ${orphaned.report}")

// ------------------------------- the NetScan writes what every AppliesTo reads (N§9)
//
// A module whose AppliesTo names a category or property the NetScan never writes, or tests
// for a value it never writes, applies to nothing, and nothing says so.

def appliesToNeeds = new File(ROOT, 'dist').listFiles().findAll { it.name.endsWith('.json') }.collectMany { f ->
    def applies = new JsonSlurper().parse(f).appliesTo ?: ''
    def needs = (applies =~ /hasCategory\("([^"]+)"\)/).collect { [module: f.name, category: it[1]] }
    needs += (applies =~ /(pve\.[a-z0-9_.]+) (==|!=) "([^"]*)"/).collect { [module: f.name, property: it[1], op: it[2], value: it[3]] }
    needs += (applies =~ /(?<![\w."])(pve\.[a-z0-9_.]+)(?![a-z0-9_.])(?! [!=]=)/).collect { it[1] }
        .findAll { it != 'pve.guest.mac_prefix' }   // set by a user, not the NetScan
        .collect { [module: f.name, property: it, op: 'present'] }
    needs
}
def unmet = { Map planned ->
    def written = planned.resources*.hostProps
    appliesToNeeds.findAll { need ->
        if (need.category) { return !written.any { (it['system.categories'] ?: '').tokenize(',').contains(need.category) } }
        if (need.op == '==') { return !written.any { it[need.property] == need.value } }
        return !written.any { it.containsKey(need.property) }
    }
}
def fullPlan = plan(nsGuestBase + [props: nsGuestBase.props + ['pve.api.url': 'https://pve-vip.example.com:8006']])
note('ns-appliesto', appliesToNeeds.size() >= 6 && unmet(fullPlan).isEmpty(),
     "AppliesTo needs the NetScan never meets: ${unmet(fullPlan)} (of ${appliesToNeeds.size()})")
// The check itself must be able to fail: with guests off, nothing is ProxmoxVE_Guest.
note('ns-appliesto-sensitive', unmet(plan(nsBase + [props: nsBase.props + ['pve.api.url': 'https://pve-vip.example.com:8006']])).any { it.property == 'pve.guest.mac' },
     'the AppliesTo cross-check could not see a missing guest property')

// ----------------------------- the NetScan body, against stand-in snippets (N§3, §9)
//
// The body is Collector-only: it loads lm.emit, lm.debug, proto.http and lm.api. Stand-in
// classes with the snippet shapes read off a Collector (lm.api's client methods, lm.emit's
// resource(List, Boolean)) let it run here against the mock API end to end.

def netscanFile = new File(ROOT, 'dist/netscan/Proxmox_VE_NetScan.groovy')
def stubClasses = new GroovyClassLoader(this.class.classLoader)
stubClasses.parseClass('''package com.santaba.agent
class AgentVersion { static String AGENT_VERSION = '41000' }''')
stubClasses.parseClass('''package com.logicmonitor.mod
class Snippets {
    static Map fakes = [:]
    static Object getLoader() { null }
}''')
stubClasses.parseClass('''package com.santaba.agent.groovy.utils
class GroovyScriptHelper {
    static Object getInstance(Object version) {
        [getScript: { String name, Object loader -> [withBinding: { Object binding ->
            [load: { String snippet, String v -> com.logicmonitor.mod.Snippets.fakes[snippet] }] }] }]
    }
}''')
// LogicMonitor's ParamMap, as a Collector hands it over: get and set, and not a java.util.Map.
stubClasses.parseClass('''package com.santaba.common.util
class ParamMap {
    private Map values = [:]
    String get(String key) { values[key] }
    String get(String key, String fallback) { values.containsKey(key) ? values[key] : fallback }
    void set(String key, String value) { values[key] = value }
}''')
def paramMap = { Map props ->
    def map = stubClasses.loadClass('com.santaba.common.util.ParamMap').getDeclaredConstructor().newInstance()
    props.each { key, value -> map.set(key, value) }
    map
}
def snippetFakes = stubClasses.loadClass('com.logicmonitor.mod.Snippets').fakes
def emitted = []
def portalDevices = []
def portalEstimateMs = 10
def portalLookup = [:]   // display name -> device
snippetFakes['lm.emit'] = [resource: { List resources, Boolean debugMode -> emitted.addAll(resources) }]
snippetFakes['lm.debug'] = [debugSnippetFactory: { Object o, Object d, Object l, String c -> [LMDebugPrint: { String m -> }] }]
snippetFakes['proto.http'] = [httpSnippetFactory: { Object props -> [getProxyInfo: { -> [:] }] }]
snippetFakes['lm.api'] = [lmApiSnippetFactory: { Object props, Object http, Object dbg ->
    [getApiId: { -> 'id' }, getApiKey: { -> 'key' }, apiCallInfo: { String what, Map args -> [timeEstimateMs: portalEstimateMs] },
     getPortalDevices: { Map args -> portalDevices },
     findPortalDevice: { String name, Map args -> portalLookup[name] },
     findPortalDeviceByName: { String name, Map args -> null },
     findPortalDeviceBySingleProperty: { String k, String v, String op, Map args -> null }] }]
def runNetscan = { Map props ->
    emitted.clear()
    def out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream()
    def realOut = System.out, realErr = System.err
    def result = null
    Throwable thrown = null
    try {
        System.setOut(new PrintStream(out, true, 'UTF-8')); System.setErr(new PrintStream(err, true, 'UTF-8'))
        result = new GroovyShell(stubClasses, new Binding([netscanProps: paramMap(props)])).evaluate(netscanFile)
    } catch (Throwable t) { thrown = t } finally { System.setOut(realOut); System.setErr(realErr) }
    [exit: result, thrown: thrown, stdout: new String(out.toByteArray(), 'UTF-8'), stderr: new String(err.toByteArray(), 'UTF-8'),
     resources: new ArrayList(emitted)]
}
// The mock API is at 127.0.0.1, which is no fixture node's address: say the cluster has none of its own.
def bodyProps = ['pve.api.url': baseUrl, 'pve.token.credential': TOKEN, 'pve.api.timeout': '2000',
                 'pve.netscan.cluster.address': '192.168.10.11']
def body = runNetscan(bodyProps)
note('netscan-body', netscanFile.exists() && body.thrown == null && body.exit == 0 && body.resources*.displayname.sort() == ['pve1', 'pve2'],
     "the NetScan body ran ${body.exit} / ${body.thrown} / ${body.resources*.displayname} / ${body.stderr.take(300)}")
// The whole console copy, filled in as a user would and run as the debug console runs it: no
// netscanProps, hostProps made by the block itself (lab 2026-10-26: it returned 1, "Cannot
// determine the Proxmox API URL").
def consoleFilled = new File(ROOT, 'dist/netscan/Proxmox_VE_NetScan.console.groovy').text
    .replace("'https://<node or cluster address>:8006'", "'" + baseUrl + "'")
    .replace("'<user@realm!tokenid=secret>'", "'" + TOKEN + "'")
    .replace("'pve.api.insecure'              : 'true',",
             "'pve.api.insecure': 'true', 'pve.api.timeout': '2000', 'pve.netscan.cluster.address': '192.168.10.11',")
emitted.clear()
def consoleOut = new ByteArrayOutputStream()
def consoleExit = null
Throwable consoleThrown = null
def realStdout = System.out
try {
    System.setOut(new PrintStream(consoleOut, true, 'UTF-8'))
    consoleExit = new GroovyShell(stubClasses, new Binding()).evaluate(consoleFilled)
} catch (Throwable t) { consoleThrown = t } finally { System.setOut(realStdout) }
def consoleStdout = new String(consoleOut.toByteArray(), 'UTF-8')
note('netscan-console-run', consoleThrown == null && consoleExit == 0 && emitted*.displayname.sort() == ['pve1', 'pve2'] &&
                            consoleStdout.contains('# set: pve.api.url, pve.token.credential') && !consoleStdout.contains(TOKEN.tokenize('=').last()),
     "the filled-in console copy ran ${consoleExit} / ${consoleThrown} / ${emitted*.displayname} / ${consoleStdout.take(400)}")
// The plain NetScan pasted into the debug console has no properties: it says which file to use.
def plainOut = new ByteArrayOutputStream()
def plainExit = null
try {
    System.setOut(new PrintStream(plainOut, true, 'UTF-8'))
    plainExit = new GroovyShell(stubClasses, new Binding()).evaluate(netscanFile)
} catch (Throwable ignored) { } finally { System.setOut(realStdout) }
note('netscan-console-hint', plainExit != 0 && new String(plainOut.toByteArray(), 'UTF-8').contains('Proxmox_VE_NetScan.console.groovy'),
     "the plain NetScan in the console did not point to the console copy: ${new String(plainOut.toByteArray(), 'UTF-8').take(300)}")
def active = body.resources.find { it.hostProps['pve.cluster.role'] == 'active' }
note('netscan-report', active?.hostProps?.get('pve.netscan.report')?.contains('no cluster address') == true,
     "the run's report was not on the active resource: ${active?.hostProps}")
// LogicMonitor recommends 32 KB at most; 31 KB here keeps a kilobyte free for the next change.
note('netscan-size', netscanFile.length() <= 31 * 1024, "the NetScan script is ${netscanFile.length()} bytes, within 1 KB of LogicMonitor's recommended 32 KB")
// The console copy's property block: LogicMonitor refuses an empty property ("Properties cannot
// be empty", lab 2026-10-26), so a value left blank is skipped, and no lab value ships in it.
def consoleText = new File(ROOT, 'dist/netscan/Proxmox_VE_NetScan.console.groovy').text
def blockStart = consoleText.indexOf('def pveConsoleProps')
def block = consoleText.substring(blockStart, consoleText.indexOf('\n', consoleText.indexOf("println('# not filled in")))
def blanked = block.replaceFirst(/('lmaccess\.id'\s*:\s*)'[^']*'/, '$1\'\'')
def strictSet = [:]
def strictProps = [set: { String key, String value ->
    if (!value) { throw new IllegalArgumentException('Properties cannot be empty') }
    strictSet[key] = value
}]
def consoleRun = runSource(blanked, [hostProps: strictProps])
note('netscan-console-props', consoleRun.thrown == null && !strictSet.containsKey('lmaccess.id') &&
                              !block.contains("'104'") && blanked != block,
     "the console property block failed on a blank value or ships a lab value: ${consoleRun.thrown} / ${strictSet.keySet()}")
// A value still holding a <placeholder> anywhere is not set, and the block says, by name only,
// what it set and what it did not ("Cannot determine the Proxmox API URL" told the user neither).
strictSet.clear()
def filled = block.replace("'<user@realm!tokenid=secret>'", "'u@pam!t=s3cret'")
def namedRun = runSource(filled, [hostProps: strictProps])
note('netscan-console-names', namedRun.thrown == null && !strictSet.containsKey('pve.api.url') &&
                              strictSet['pve.token.credential'] == 's3cret'.with { 'u@pam!t=' + it } &&
                              namedRun.stdout.contains('not filled in') && namedRun.stdout.contains('pve.api.url') &&
                              namedRun.stdout.contains('pve.token.credential') && !namedRun.stdout.contains('s3cret'),
     "the console block set a placeholder or did not name what it set: ${strictSet.keySet()} / ${namedRun.stdout.take(300)}")
// Guests on, the portal listed: guest 101 on pve1 has a config and is created.
def withGuestsBody = withFixtures(['/nodes/pve1/qemu/101/config': [name: 'web-01', net0: 'virtio=BC:24:11:0E:00:02,bridge=vmbr0', ipconfig0: 'ip=192.168.10.50/24']]) {
    runNetscan(bodyProps + ['pve.netscan.guests': 'true', 'pve.netscan.guests.filter.vmid': '101'])
}
note('netscan-guests', withGuestsBody.exit == 0 && withGuestsBody.resources.any { it.hostname == '192.168.10.50' && it.hostProps['pve.vmid'] == '101' },
     "the NetScan did not create guest 101: ${withGuestsBody.resources*.hostname} / ${withGuestsBody.stderr.take(300)}")

// ----------------------------------------- final review fixes (2026-10-26)

// C1: a node's DNS name, or a name resolving to a node's address, is that node, not a cluster address.
note('ns-node-fqdn', !plan(nsBase + [props: nsBase.props + ['pve.api.url': 'https://pve1.example.com:8006']]).resources
                        .any { it.hostProps['system.categories'] == 'ProxmoxVE_Cluster' },
     'a node FQDN in pve.api.url was taken for a cluster address')
note('ns-node-resolved', !plan(nsBase + [addressIps: ['192.168.10.11'],
                                         props: nsBase.props + ['pve.api.url': 'https://alias.example.com:8006']]).resources
                            .any { it.hostProps['system.categories'] == 'ProxmoxVE_Cluster' },
     'a name resolving to a node address was taken for a cluster address')
note('ns-vip-resolved', plan(nsBase + [addressIps: ['10.9.9.9'],
                                       props: nsBase.props + ['pve.api.url': 'https://pve-vip.example.com:8006']]).resources
                           .any { it.hostname == 'pve-vip.example.com' },
     'a VIP resolving to no node address was not a cluster address')

// M2: round-robin DNS is more than one address of one family; one IPv4 plus one IPv6 is dual stack.
def rr = runSource(preamble + '\n' + netscanLib + '''
return [pveNsRoundRobin(['10.0.0.5', 'fd00::5']), pveNsRoundRobin(['10.0.0.5', '10.0.0.6']),
        pveNsRoundRobin(['fd00::5', 'fd00::6', '10.0.0.5']), pveNsRoundRobin(['10.0.0.5'])]''', [netscanProps: [:]])
note('ns-round-robin', rr.exit == [false, true, true, false], "round-robin detection gave ${rr.exit} / ${rr.thrown}")

// C2: on a portal too large to list, guests are still looked up before they are sent.
portalEstimateMs = 10 ** 9
portalLookup['web-01'] = [id: 31, name: '192.168.9.9', displayName: 'web-01', customProperties: []]
def largePortal = withFixtures(['/nodes/pve1/qemu/101/config': [name: 'web-01', net0: 'virtio=BC:24:11:0E:00:02,bridge=vmbr0', ipconfig0: 'ip=192.168.10.50/24']]) {
    runNetscan(bodyProps + ['pve.netscan.guests': 'true', 'pve.netscan.guests.filter.vmid': '101'])
}
portalEstimateMs = 10
portalLookup.clear()
note('netscan-large-portal', largePortal.exit == 0 && !largePortal.resources.any { it.hostProps['pve.vmid'] == '101' } &&
                             largePortal.stderr.contains('collision'),
     "on a large portal guest 101 was sent unchecked: ${largePortal.resources*.hostname} / ${largePortal.stderr.take(300)}")

// I1: 'none' is no MAC, and a guest whose config was not read keeps the MAC it had.
note('guest-eri-none', eriMac(['pve.guest.mac': 'none']) == null, 'pve.guest.mac=none was used as a MAC')
def keptMac = plan(nsGuestBase + [configs: nsGuestBase.configs.findAll { it.key != 101 },
                                  existing: [[id: 77, hostname: '192.0.2.101', displayName: 'web (101)',
                                              props: ['pve.serial': 'pve-cluster::101', 'pve.guest.mac': 'bc:24:11:00:01:01']]]])
note('ns-mac-kept', keptMac.resources.find { it.hostProps['pve.serial'] == 'pve-cluster::101' }?.hostProps?.get('pve.guest.mac') == 'bc:24:11:00:01:01',
     "a guest whose config was not read lost its MAC: ${keptMac.resources.find { it.hostProps['pve.serial'] == 'pve-cluster::101' }?.hostProps}")

// I2: an orphan is a resource whose guest or node is gone from Proxmox, nothing else.
def notOrphan = plan(nsGuestBase + [existing: [[id: 81, hostname: 'dhcp-ct', displayName: 'dhcp-ct',
                                                props: ['pve.cluster': 'pve-cluster', 'pve.serial': 'pve-cluster::103']]]])
note('ns-orphan-present', !(notOrphan.orphans*.id).contains(81) && !notOrphan.report.any { it.contains('orphan') && it.contains('dhcp-ct') },
     "a guest still in Proxmox was reported as an orphan: ${notOrphan.orphans} / ${notOrphan.report}")

// I3: a resource that loses the role is sent with role none; a role resource that cannot be sent
// does not leave half a pair.
def vipNodesOff = plan(nsBase + [existing: [[id: 2, hostname: 'fd00::12', displayName: 'pve2',
                                             props: ['pve.cluster': 'pve-cluster', 'pve.serial': 'pve-cluster::node::pve2', 'pve.cluster.role': 'standby']]],
                                 props: nsBase.props + ['pve.api.url': 'https://pve-vip.example.com:8006', 'pve.netscan.nodes': 'false']])
note('ns-role-dropped', vipNodesOff.resources.find { it.hostProps['pve.serial'] == 'pve-cluster::node::pve2' }?.hostProps?.get('pve.cluster.role') == 'none',
     "a former standby left out by nodes=false kept its role: ${vipNodesOff.resources*.hostProps*.get('pve.serial')}")
def vipGone = plan(nsBase + [existing: [[id: 3, hostname: 'pve-vip.example.com', displayName: 'pve-cluster',
                                         props: ['pve.cluster': 'pve-cluster', 'pve.serial': 'pve-cluster', 'pve.cluster.role': 'active']]]])
def oldCluster = vipGone.resources.find { it.hostname == 'pve-vip.example.com' }
note('ns-role-address-gone', oldCluster?.hostProps?.get('pve.cluster.role') == 'none' && !(vipGone.orphans*.id).contains(3),
     "a cluster resource whose address went kept its role, or was called an orphan: ${oldCluster} / ${vipGone.orphans}")
// The groups not pinned above: a re-sent role resource keeps its own, and a standalone host's
// guests sit under its Host group.
def vipNodesOffPve2 = vipNodesOff.resources.find { it.hostProps['pve.serial'] == 'pve-cluster::node::pve2' }
def standaloneGuests = plan(nsGuestBase + [status: [[type: 'node', name: 'pve-a', nodeid: 1, online: 1, local: 1, ip: '10.0.0.9']],
                                           reach: ['pve-a': [api: true, ping: true]],
                                           props: nsGuestBase.props + ['pve.api.url': 'https://10.0.0.9:8006']])
note('ns-groups-rest', vipNodesOffPve2?.groupName == ['Proxmox VE/Cluster - pve-cluster/Nodes'] &&
                       oldCluster?.groupName == ['Proxmox VE/Cluster - pve-cluster'] &&
                       standaloneGuests.resources.find { it.hostProps['system.categories'] == 'ProxmoxVE_Guest' }?.groupName ==
                           ['Proxmox VE/Host - pve-a/Guests'],
     "groups were ${vipNodesOffPve2?.groupName} / ${oldCluster?.groupName} / ${standaloneGuests.resources*.groupName}")
def activeClash = plan(nsBase + [existing: [[id: 9, hostname: '10.99.0.1', displayName: 'pve1', props: [:]]]])
def survivor = activeClash.resources.find { it.hostProps['pve.node'] == 'pve2' }
note('ns-role-collision', !activeClash.resources.any { it.hostProps['pve.node'] == 'pve1' } &&
                          survivor?.hostProps?.get('pve.cluster.role') == 'active' &&
                          activeClash.report.any { it.toLowerCase().contains('role') && it.contains('pve1') },
     "a colliding active node left a standby behind it: ${survivor?.hostProps} / ${activeClash.report}")

// I4: a node online in the cluster whose API the Collector cannot reach is unreachable.
def apiDown = plan(nsBase + [status: statusWith([:], [:], 1).collect { it.name == 'pve1' ? it + [ip: '192.168.10.11'] : it },
                             reach: [pve1: [api: true, ping: true], pve2: [api: false, ping: true, why: 'API at fd00::12 did not answer']]])
note('ns-reach-unreachable', apiDown.resources.find { it.hostProps['pve.node'] == 'pve2' }?.hostProps?.get('pve.netscan.reachable') == 'false',
     'a node online in the cluster whose API did not answer was not marked unreachable')

// I5: a request for one node fails over at most once: every address only proxies to that node.
def bases = runSource(preamble + '\nreturn [pveBasesFor("/nodes/pve1/status").size(), pveBasesFor("/cluster/status").size()]',
    [hostProps: hostProps + ['pve.api.hosts': '127.0.0.1:1,127.0.0.1:2,127.0.0.1:3,127.0.0.1:4']])
note('failover-node-path', bases.exit == [2, 5], "addresses tried per path were ${bases.exit} / ${bases.thrown}")
def statusBudget = runScript(new File(scriptsDir, 'Proxmox_VE_Cluster_Status.collect.groovy'), [hostProps: hostProps + ['pve.api.budget.ms': '0']])
note('status-budget', statusBudget.exit == 0 && statusBudget.stdout.contains('Quorate=') && statusBudget.stderr.contains('time budget'),
     "Cluster_Status ignored the time budget: ${statusBudget.exit} / ${statusBudget.stderr.take(200)}")

// ------------------------------- scale: 16 nodes, 1,000 guests (A§3.9, Q6)
//
// The design's call budgets, asserted per script, so a change that adds an O(guests) or an
// O(nodes) call fails here rather than on a customer's cluster.

def scale = new GroovyShell().evaluate(new File(ROOT, 'tests/fixtures/scale.groovy'))
def atScale = { String file, Map props = hostProps ->
    withFixtures(scale) {
        long started = System.currentTimeMillis()
        def run = discoverWith(new File(scriptsDir, file), props)
        run.millis = System.currentTimeMillis() - started
        run
    }
}
def scaleStatusAd = atScale('Proxmox_VE_Cluster_GuestStatus.ad.groovy')
note('scale-guest-ad', scaleStatusAd.exit == 0 && scaleStatusAd.requests.count { it.endsWith('/config') } == 1000 &&
                       scaleStatusAd.requests.size() <= 1003 && scaleStatusAd.stdout.readLines().count { it.trim() } == 1000,
     "GuestStatus discovery at scale: exit ${scaleStatusAd.exit}, ${scaleStatusAd.requests.size()} requests, " +
     "${scaleStatusAd.stdout.readLines().count { it.trim() }} instances / ${scaleStatusAd.stderr.take(200)}")
note('scale-budget', scaleStatusAd.millis < 45000 && !scaleStatusAd.stderr.contains('time budget'),
     "GuestStatus discovery took ${scaleStatusAd.millis} ms at scale")
def scaleSnap = atScale('Proxmox_VE_Cluster_Snapshots.collect.groovy')
note('scale-snapshots', scaleSnap.exit == 0 && scaleSnap.requests.size() <= 1002 &&
                        parseBatch(scaleSnap.stdout).size() == 1000,
     "Snapshots at scale: exit ${scaleSnap.exit}, ${scaleSnap.requests.size()} requests, ${parseBatch(scaleSnap.stdout).size()} guests")
def scaleStatus = atScale('Proxmox_VE_Cluster_Status.collect.groovy')
note('scale-cluster-status', scaleStatus.exit == 0 && scaleStatus.requests.size() <= 4 + 2 * 16 + 16,
     "Cluster_Status at scale made ${scaleStatus.requests.size()} requests: ${scaleStatus.requests.countBy { it.replaceAll('pve[0-9]+', 'N') }}")
['Proxmox_VE_Cluster_GuestPerformance.collect.groovy', 'Proxmox_VE_Cluster_StorageCapacity.collect.groovy'].each { f ->
    def bulk = atScale(f)
    note('scale-bulk', bulk.exit == 0 && bulk.requests.size() == 1, "${f} made ${bulk.requests.size()} requests at scale")
}

server.stop(0)
server2.stop(0)

// -------------------------------------------------------------------- report

println ''
println 'API paths exercised:'
requested.sort().each { println '  ' + it }
println ''

if (failures) {
    println "FAILED  ${failures.size()} of ${checks} checks"
    failures.each { println '  - ' + it }
    System.exit(1)
}
println "PASSED  all ${checks} checks"
System.exit(0)
