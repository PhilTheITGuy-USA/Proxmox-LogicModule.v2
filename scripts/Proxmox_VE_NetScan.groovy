/*
 * The Proxmox VE NetScan: an Enhanced Script NetScan, one per cluster, run hourly. It reads
 * the cluster through its API, decides the resources with pveNetscanPlan (pve_netscan.groovy,
 * tested in the harness) and emits them through lm.emit. Only the reads and the snippet calls
 * live here; every decision is in the plan.
 *
 * Properties come from netscanProps in a NetScan, or from hostProps when run from the
 * Collector debug console against a resource -- where debug mode is forced on, so lm.emit
 * masks every .credential value it prints.
 */
import com.logicmonitor.mod.Snippets
import com.santaba.agent.AgentVersion
import com.santaba.agent.groovy.utils.GroovyScriptHelper as GSH

def inConsole = !binding.hasVariable('netscanProps')
def debug = inConsole || pveHostProp('pve.netscan.debug', 'false') == 'true'
// A NetScan's stderr is not shown anywhere in the portal; the debug console shows stdout.
def say = { String message -> inConsole ? println('# ' + message) : System.err.println(message) }

// The Enhanced Script NetScan needs Collector 32.400 or later.
def collectorVersion = AgentVersion.AGENT_VERSION.toInteger()
if (collectorVersion < 32400) {
    say('This NetScan needs Collector 32.400 or later; this one runs ' +
        String.format('%.3f', collectorVersion / 1000.0d) + '.')
    return 1
}
if (pveConfigError) {
    // In the debug console there are no NetScan properties: only the console copy sets them.
    say(pveConfigError + (inConsole ? ' In the debug console, run Proxmox_VE_NetScan.console.groovy with its properties filled in.' : ''))
    return 1
}

def loader = GSH.getInstance(GroovySystem.version).getScript('Snippets', Snippets.getLoader()).withBinding(getBinding())
def emit = loader.load('lm.emit', '1.1')
def report = []

try {
    // ---------------------------------------------------------------- Proxmox
    def status = pveGet('/cluster/status') ?: []
    def resources = pveGet('/cluster/resources') ?: []
    def nodeRows = status.findAll { it.type?.toString() == 'node' && it.name }

    // Each node asked directly at the address its resource will have, and pinged: a node
    // resource that does not answer ping is dead in LogicMonitor whatever its modules
    // collect. The ping is InetAddress.isReachable, a real ICMP echo only where the
    // Collector has the privilege for one, so a failure here is a hint, not proof.
    def byName = pveHostProp('pve.netscan.node.hostname', 'ip') == 'name'
    def maxFailures = pveHostProp('pve.netscan.node.maxfailures', '10') as int
    int failures = 0
    def reach = [:]
    nodeRows.each { row ->
        def name = row.name.toString()
        if (row.online?.toString() != '1' && row.online != null) {
            reach[name] = [api: false, ping: false, why: 'offline in /cluster/status']
            return
        }
        if (failures >= maxFailures) {
            reach[name] = [api: false, ping: false, why: 'not checked: ' + maxFailures + ' node checks already failed this run']
            return
        }
        def host = byName ? name : (row.ip?.toString() ?: name)
        def entry = [api: false, ping: false]
        try {
            pveGetAt(pveHostUrl(host), '/nodes/' + name + '/status')
            entry.api = true
        } catch (Exception unreachable) {
            failures++
            entry.why = 'API at ' + host + ' did not answer: ' + unreachable.message
        }
        try {
            entry.ping = InetAddress.getByName(host.replaceAll(/^\[|\]$/, '')).isReachable(pveTimeout)
        } catch (Exception ignored) { }
        reach[name] = entry
    }

    // The cluster's own address, if it has one: it must answer ping and 8006.
    def address = pveNsHost(pveHostProp('pve.netscan.cluster.address') ?: pveHostProp('pve.api.url'))
    // What it resolves to tells a node's other name from a cluster address (pveNetscanPlan).
    def addressIps = []
    if (address && pveTopoIp(address) == null) {
        try { addressIps = InetAddress.getAllByName(address)*.hostAddress.unique() } catch (Exception ignored) { }
    }
    def clusterAddress = pveNetscanPlan([status: status, props: pveProps, addressIps: addressIps])
        .resources.any { it.hostProps['pve.node'] == null }
    if (clusterAddress) {
        if (pveAddressDown(address)) {
            report << ('cluster address ' + address + ' does not answer ping or port 8006: its resource will show as down; the standby collects')
        }
        if (pveNsRoundRobin(addressIps)) {
            report << ('cluster address ' + address + ' is round-robin DNS (' + addressIps.join(', ') +
                       '): LogicMonitor\'s ping may reach a node that is down; a VIP or proxy is safer')
        }
    }

    // ------------------------------------------------------------ the portal
    // lm.api lists the portal's devices for deduplication. It reads the LogicMonitor API
    // credentials from the NetScan's properties itself; without them there is no lookup.
    def existing = null
    def lmApi = null
    if (pveHostProp('skip.device.dedupe', 'false') != 'true') {
        try {
            def stream = binding.hasVariable('out') ? out : System.out
            def lmDebug = loader.load('lm.debug', '1').debugSnippetFactory(stream, debug, false, 'proxmox-ve-netscan')
            def http = loader.load('proto.http', '0').httpSnippetFactory(pveProps)
            lmApi = loader.load('lm.api', '0').lmApiSnippetFactory(pveProps, http, lmDebug)
            if (!lmApi.getApiId() || !lmApi.getApiKey()) { lmApi = null }
        } catch (Exception noApi) {
            report << ('LogicMonitor API unavailable, no duplicate checking: ' + noApi.message)
            lmApi = null
        }
        if (lmApi == null) { report << 'no LogicMonitor API credentials: duplicate checking is off' }
    }
    def toDevice = { Map d ->
        def custom = (d.customProperties ?: []).collectEntries { [(it.name?.toString()): it.value?.toString()] }
        [id: d.id, hostname: d.name?.toString(), displayName: d.displayName?.toString(), props: custom]
    }
    def args = [size: 1000, fields: 'id,name,displayName,currentCollectorId,customProperties']
    def everyDevice = true
    if (lmApi != null) {
        def limitSec = Math.min(Math.max(pveHostProp('lmapi.timelimit.sec', '60') as int, 30), 120)
        def estimate = lmApi.apiCallInfo('Devices', args)
        if ((estimate?.timeEstimateMs ?: 0) > limitSec * 1000L) {
            everyDevice = false
            report << ('portal too large to list in ' + limitSec + ' s (lmapi.timelimit.sec): devices are looked up one at a time, and orphans are not reported')
            existing = []
        } else {
            existing = (lmApi.getPortalDevices(args) ?: []).collect { toDevice(it) }
        }
    }

    // ------------------------------------------------------------- guests
    def configs = [:]
    def addresses = [:]
    if (pveHostProp('pve.netscan.guests', 'false') == 'true' && existing != null) {
        def selected = resources.findAll { it.type in ['qemu', 'lxc'] && it.vmid != null }
                                .findAll { pveGuestSelected(it, pveProps) }
        pveForEachGuest('Config reads', selected, status, '/config', { guest, config, error ->
            if (config instanceof Map) { configs[guest.vmid as int] = config }
        })
        def live = [:]
        def running = selected.findAll { it.status?.toString() == 'running' }
        pveForEachGuest('Container address reads', running.findAll { it.type == 'lxc' }, status, '/interfaces',
            { guest, data, error -> if (data != null) { live[guest.vmid as int] = data } }, false)
        int agentDenied = 0
        def withAgent = running.findAll { it.type == 'qemu' && configs[it.vmid as int]?.agent?.toString() ==~ /^(1|enabled=1)(,.*)?$/ }
        pveForEachGuest('Guest agent reads', withAgent, status, '/agent/network-get-interfaces', { guest, data, error ->
            if (data != null) { live[guest.vmid as int] = data }
            if (error != null && pveFailureStatus(error) == 403) { agentDenied++ }
        }, false)
        if (agentDenied) {
            report << (agentDenied + ' guest agent reads refused (HTTP 403): the token lacks VM.GuestAgent.Audit ' +
                       '(VM.Monitor on PVE 8); those VMs use their configured address')
        }
        selected.each { guest ->
            def vmid = guest.vmid as int
            def found = pveGuestAddress(guest, configs[vmid], live[vmid])
            if (found) { addresses[vmid] = found }
        }
    }

    // --------------------------------------------------------------- plan
    def input = [status: status, resources: resources, reach: reach, configs: configs,
                 addresses: addresses, props: pveProps, existing: existing, addressIps: addressIps]
    if (lmApi != null && !everyDevice) {
        // A large portal: look up only the devices this run would send.
        def found = [:]
        pveNetscanPlan(input + [existing: []]).resources.each { res ->
            def serial = res.hostProps['pve.serial']
            // findPortalDeviceBySingleProperty's argument order is read off its signature,
            // (String, String, String, Map); a refusal falls back to name and hostname.
            def match = null
            try { match = lmApi.findPortalDeviceBySingleProperty('pve.serial', serial, '=', args) } catch (Exception ignored) { }
            match = match ?: lmApi.findPortalDevice(res.displayname, args) ?: lmApi.findPortalDeviceByName(res.hostname, args)
            if (match) { found[match.id] = toDevice(match) }
        }
        input.existing = found.values() as List
    }
    def planned = pveNetscanPlan(input)
    report.addAll(0, planned.report)
    planned.hostnameUpdates.each { update ->
        report << ('resource ' + update.id + ': change its hostname to ' + update.hostname + ' under Manage (lm.api cannot)')
    }

    // The run's report rides on the resource that collects for the cluster, so it can be
    // read in the portal after a scheduled run, where stderr is not shown.
    def collecting = planned.resources.find { it.hostProps['pve.cluster.role'] == 'active' }
    if (collecting) { collecting.hostProps['pve.netscan.report'] = report.join(' | ').take(4000) }
    report.each { say(it) }

    emit.resource(planned.resources, debug)
    return 0
} catch (Exception failure) {
    say('Proxmox VE NetScan failed: ' + failure.class.simpleName + ': ' + failure.message)
    return 1
}
