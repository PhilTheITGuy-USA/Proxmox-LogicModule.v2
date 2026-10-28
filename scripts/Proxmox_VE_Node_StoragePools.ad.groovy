/*
 * Active Discovery: this node's storage pools -- ZFS pools, LVM volume groups and LVM thin
 * pools, asked of the node itself.
 *
 * The wildvalue is the pool's kind and name ("zfs/rpool", "lvmthin/pve/data"), which stay put
 * however the stores on top of them change. Each instance carries the pool vertex
 * Proxmox_VE_Cluster_Topology draws between a store and its disks, type StorageAggregate.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodeName = pveSelfNode()
    def cluster = pveCarrierCluster('Storage pool', true)
    pveNodePools(nodeName).each { pool ->
        def size = pveSizeLabel(pool.size)
        pveDiscover(
            pool.id,
            pool.name + ' (' + pool.label + (size ? ', ' + size : '') + ')',
            pool.label + ' ' + pool.name + ' on ' + nodeName,
            [
                pve_node     : nodeName,
                pve_pool     : pool.name,
                pve_pool_type: pool.kind
            ],
            cluster ? pveEri(pvePoolKey(cluster, nodeName, pool.kind, pool.name), 'StorageAggregate') : [:]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox storage pool discovery failed: ' + exception.message)
    return 2
}
