/*
 * BatchScript collection: size, use and health of this node's storage pools.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    pveNodePools(pveSelfNode()).each { pool ->
        def id = pool.id
        pveEmit(id, 'SizeGB', pveGB(pool.size))
        pveEmit(id, 'UsedGB', pveGB(pool.used))
        pveEmit(id, 'FreeGB', pveGB(pool.free > 0 ? pool.free : 0))
        if (pool.kind == 'lvm') {
            // A volume group is nearly all allocated to its volumes by design, so its share is
            // allocation, not fullness, and carries no alert.
            pveEmit(id, 'AllocatedPercent', pvePercent(pool.used, pool.size))
        } else {
            pveEmit(id, 'UsedPercent', pvePercent(pool.used, pool.size))
        }
        if (pool.kind == 'zfs' && pool.health) {
            // ONLINE is the only state in which every vdev is working.
            pveEmit(id, 'Healthy', pool.health == 'ONLINE' ? 1 : 0)
        }
        if (pool.kind == 'zfs' && pool.frag != null && pool.frag.toString().isNumber()) {
            pveEmit(id, 'FragmentationPercent', pool.frag)
        }
        if (pool.kind == 'lvmthin' && pool.metaSize > 0) {
            pveEmit(id, 'MetadataUsedPercent', pvePercent(pool.metaUsed, pool.metaSize))
        }
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox storage pool collection failed: ' + exception.message)
    return 2
}
