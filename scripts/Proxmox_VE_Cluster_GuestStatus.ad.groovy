/*
 * Active Discovery: QEMU virtual machines and LXC containers, cluster-wide, for
 * Proxmox_VE_Cluster_GuestStatus alone. Same instances and wildvalues as Proxmox_VE_Cluster_Guests.ad,
 * which GuestPerformance uses, so a guest's status and performance instances match.
 *
 * What it adds, from one config read per guest:
 *
 * - auto.pve_onboot, 1 when the guest is set to start at boot (Proxmox's "Start at boot",
 *   onboot in the config, off when absent), else 0. Collection alerts on a guest that should
 *   be running by that statement of intent and is not; a guest stopped on purpose is not
 *   set to start at boot.
 * - the key Proxmox_VE_Cluster_Topology draws that guest under, so the map can resolve every
 *   guest to an instance -- including guests with no LogicMonitor resource of their own. The
 *   key is the MAC of the first virtual NIC, or for a guest with no NIC the same pveTopoKey
 *   fallback the TopologySource emits. Only this module carries it, as only VMware's status
 *   module does, so each guest's key sits on one instance.
 *
 * Both are optional extras; the instance is not. Every failure on the way -- gating calls, a
 * config read, a guest on an offline node -- leaves the guest discovered without them. After
 * a node's first failed config read, that node's later guests are not read at all this run.
 * Only /cluster/resources?type=vm failing, or answering no guests (an under-privileged token),
 * exits 2.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def guests = pveGuestList()

    // Only the collecting resource reads configs: a standby's keys would double every key,
    // and its collection prints nothing to use onboot with.
    def readConfigs = pveRole().collect

    /*
     * The keys are written plain, not through lm.topo's eriPreProcessor, so this script
     * stays compilable and testable here. With no namespace or blacklist the two are the
     * same; with either set the TopologySource's keys change and these would silently stop
     * matching, so the keys step aside and say why. onboot is still read.
     */
    def stampKeys = readConfigs
    def blocking = ['topo.namespace', 'topo.blacklist'].findAll { pveHostProp(it) }
    if (blocking && readConfigs) {
        System.err.println('Guest instance ERIs skipped: ' + blocking.join(' and ') +
            ' is set, which rewrites the topology keys these would have to match.')
        stampKeys = false
    }

    def clusterName = null
    def statusRows = []
    if (readConfigs) {
        try {
            statusRows = pveGet('/cluster/status') ?: []
            clusterName = pveTopoClusterName(statusRows)
        } catch (Exception gateException) {
            System.err.println('Guest configs not read: ' + gateException.message)
            readConfigs = false
        }
    }

    if (!readConfigs) {
        guests.each { guest -> pveGuestDiscover(guest, [:]) }
        return 0
    }

    /*
     * One config read per guest, through pveForEachGuest: asked of the node that owns the
     * guest, never of an offline node, stopped for a node after its first failure, bounded in
     * concurrency and time. Every guest is still discovered; one whose config was not read
     * gets neither onboot nor a key this run rather than the NIC-less fallback, which would
     * change its identity for one run -- the same choice the TopologySource makes.
     */
    pveForEachGuest('Config reads', guests, statusRows, '/config', { guest, config, error ->
        def raw = [:]
        def extra = [:]
        if (config instanceof Map) {
            extra.pve_onboot = config.onboot?.toString() in ['1', 'true'] ? '1' : '0'
            if (stampKeys) {
                raw['predef.externalResourceID'] = pveGuestMac(config) ?:
                    pveTopoKey(clusterName, guest.type + '-' + guest.vmid)
                raw['predef.externalResourceType'] = 'VirtualMachine'
            }
        }
        pveGuestDiscover(guest, raw, extra)
    })
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox guest discovery failed: ' + exception.message)
    return 2
}
