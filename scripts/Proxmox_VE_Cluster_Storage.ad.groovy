/*
 * Active Discovery: storage, per node.
 *
 * A shared storage (Ceph, NFS, iSCSI) is visible from every node and is therefore
 * discovered once per node. That is intentional: availability and free space are
 * reported from the perspective of the node doing the asking, and a shared store can
 * be reachable from one node and not another.
 *
 * Each instance's name says what kind of store it is: local (one node's own disk), shared
 * (this cluster's Ceph, or a store Proxmox is told every node sees) or network (NFS, SMB,
 * iSCSI, Proxmox Backup Server, an external Ceph). /storage tells an external Ceph from this
 * cluster's own; without it a Ceph store reads as shared.
 *
 * The instances also carry the storage vertices Proxmox_VE_Cluster_Topology draws, type
 * VMDatastore as in LogicMonitor's vSphere suite: a local store one per node, a shared or
 * network store one for the cluster, carried by the instance of the first node by name.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def stores = pveGet('/cluster/resources?type=storage') ?: []

    if (stores.isEmpty()) {
        System.err.println('/cluster/resources returned no storage. Verify the API token has ' +
            'Datastore.Audit on /storage.')
    }

    def cfgs = []
    try {
        cfgs = pveGet('/storage') ?: []
    } catch (Exception unreadable) {
        System.err.println('Storage definitions not read, so an external Ceph reads as shared: ' + unreadable.message)
    }
    def cluster = pveCarrierCluster('Storage', false)
    def vertices = pveStorageVertices(cluster ?: '', stores, cfgs)

    stores.each { store ->
        def vertex = vertices[store.id?.toString()]
        def kind = vertex?.kind ?: (store.shared ? 'shared' : 'local')
        def type = store.plugintype ?: 'unknown'
        // The size in the name follows the instance onto every graph and dashboard legend.
        def size = pveSizeLabel(store.maxdisk)
        pveDiscover(
            store.id,
            store.storage + ' on ' + store.node + ' (' + kind + ', ' + type + (size ? ', ' + size : '') + ')',
            'Proxmox ' + kind + ' storage "' + store.storage + '" (' + type + ')',
            [
                pve_id          : store.id,
                pve_node        : store.node,
                pve_storage     : store.storage,
                pve_type        : store.plugintype,
                pve_shared      : store.shared ? 'true' : 'false',
                pve_storage_kind: kind
            ],
            cluster && vertex?.carries ? pveEri(vertex.key, 'VMDatastore') : [:]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox storage discovery failed: ' + exception.message)
    return 2
}
