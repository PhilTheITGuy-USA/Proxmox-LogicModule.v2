/*
 * BatchScript collection: use and replication of this cluster's Ceph pools.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}
// Only the resource that holds the cluster role now collects; the other prints nothing.
if (!pveRole('Proxmox_VE_Cluster_CephPools').collect) {
    return 0
}

try {
    def pools = pveCephPools()
    if (pools == null) {
        System.err.println('No Ceph pools available from any online node.')
        return 2
    }
    pools.each { pool ->
        def id = pool.pool_name.toString()
        pveEmit(id, 'UsedGB', pveGB(pool.bytes_used))
        if (pool.percent_used != null) {
            // Ceph reports a fraction of the pool's usable space; some releases a percentage.
            def used = pool.percent_used as double
            pveEmit(id, 'UsedPercent', pveRound(used <= 1.0d ? used * 100.0d : used))
        }
        if (pool.size != null) { pveEmit(id, 'Replicas', pool.size) }
        if (pool.min_size != null) { pveEmit(id, 'MinReplicas', pool.min_size) }
        if (pool.pg_num != null) { pveEmit(id, 'PlacementGroups', pool.pg_num) }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox Ceph pool collection failed: ' + exception.message)
    return 2
}
