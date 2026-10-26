/*
 * Active Discovery: QEMU virtual machines and LXC containers, cluster-wide, for
 * Proxmox_VE_Cluster_GuestStatus alone. Same instances and wildvalues as Proxmox_VE_Cluster_Guests.ad,
 * which GuestPerformance uses, so a guest's status and performance instances match.
 *
 * What it adds: each guest's instance carries the key
 * Proxmox_VE_Cluster_Topology draws that guest under, so the map can resolve every guest to an
 * instance -- including guests with no LogicMonitor resource of their own. The key is the
 * MAC of the first virtual NIC, or for a guest with no NIC the same pveTopoKey fallback
 * the TopologySource emits. Only this module carries it, as only VMware's status module
 * does, so each guest's key sits on one instance.
 *
 * The map is the optional extra; the instance is not. Every failure on the way to a key
 * -- gating calls, a config read, a guest on an offline node -- leaves the guest
 * discovered without one. After a node's first failed config read, that node's later
 * guests are not read at all this run. Only /cluster/resources?type=vm failing exits 2.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def guests = pveGuestList()

    /*
     * The keys are written plain, not through lm.topo's eriPreProcessor, so this script
     * stays compilable and testable here. With no namespace or blacklist the two are the
     * same; with either set the TopologySource's keys change and these would silently stop
     * matching, so the feature steps aside and says why.
     */
    // Only the collecting resource stamps instance ERIs: a standby's would double every key.
    def wanted = pveRole().collect
    def blocking = ['topo.namespace', 'topo.blacklist'].findAll { pveHostProp(it) }
    if (blocking) {
        System.err.println('Guest instance ERIs skipped: ' + blocking.join(' and ') +
            ' is set, which rewrites the topology keys these would have to match.')
        wanted = false
    }

    def clusterName = null
    def statusRows = []
    if (wanted) {
        try {
            statusRows = pveGet('/cluster/status') ?: []
            clusterName = pveTopoClusterName(statusRows)
        } catch (Exception gateException) {
            System.err.println('Guest instance ERIs skipped: ' + gateException.message)
            wanted = false
        }
    }

    if (!wanted) {
        guests.each { guest -> pveGuestDiscover(guest, [:]) }
        return 0
    }

    /*
     * One config read per guest, through pveForEachGuest: asked of the node that owns the
     * guest, never of an offline node, stopped for a node after its first failure, bounded in
     * concurrency and time. Every guest is still discovered; one whose config was not read
     * gets no key this run rather than the NIC-less fallback, which would change its identity
     * for one run -- the same choice the TopologySource makes.
     */
    pveForEachGuest('Config reads', guests, statusRows, '/config', { guest, config, error ->
        def raw = [:]
        if (config instanceof Map) {
            raw['predef.externalResourceID'] = pveGuestMac(config) ?:
                pveTopoKey(clusterName, guest.type + '-' + guest.vmid)
            raw['predef.externalResourceType'] = 'VirtualMachine'
        }
        pveGuestDiscover(guest, raw)
    })
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox guest discovery failed: ' + exception.message)
    return 2
}
