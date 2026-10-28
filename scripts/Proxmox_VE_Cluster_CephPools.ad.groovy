/*
 * Active Discovery: this cluster's Ceph pools.
 *
 * Every node answers /nodes/{node}/ceph/pool for the whole cluster, so the first online node
 * that answers is enough. A cluster without Ceph discovers nothing and exits 0. The wildvalue
 * is the pool name. Each instance carries the pool vertex Proxmox_VE_Cluster_Topology draws
 * under an RBD store, type StorageAggregate.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def pools = pveCephPools()
    if (pools == null) {
        System.err.println('No Ceph pools available from any online node. This is expected on a ' +
            'cluster that does not run Ceph.')
        return 0
    }
    def cluster = pveCarrierCluster('Ceph pool', false)
    pools.each { pool ->
        def name = pool.pool_name.toString()
        pveDiscover(
            name,
            name + ' (Ceph pool' + (pool.size != null ? ', ' + pool.size + ' replicas' : '') + ')',
            'Ceph pool ' + name + (pool.crush_rule_name ? ', CRUSH rule ' + pool.crush_rule_name : ''),
            [
                pve_ceph_pool : name,
                pve_crush_rule: pool.crush_rule_name,
                pve_pool_type : pool.type
            ],
            cluster ? pveEri(pveTopoVertexKey(cluster, ['cephpool', name]), 'StorageAggregate') : [:]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox Ceph pool discovery failed: ' + exception.message)
    return 2
}
