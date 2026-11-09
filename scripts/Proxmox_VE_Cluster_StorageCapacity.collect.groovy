/*
 * BatchScript collection: storage capacity and availability, cluster-wide.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}
// Only the resource that holds the cluster role now collects; the other prints nothing.
if (!pveRole('Proxmox_VE_Cluster_StorageCapacity').collect) {
    return 0
}

try {
    def stores = pveGet('/cluster/resources?type=storage') ?: []

    stores.each { store ->
        def id = store.id
        def used = store.disk ?: 0
        def total = store.maxdisk ?: 0

        // "available" is the status Proxmox reports for a storage it can actually reach.
        def available = store.status?.toString() == 'available'
        pveEmit(id, 'Status', available ? 1 : 0)
        pveEmit(id, 'UsedGB', pveGB(used))
        pveEmit(id, 'CapacityGB', pveGB(total))
        pveEmit(id, 'FreeGB', pveGB(total > used ? total - used : 0))
        pveEmit(id, 'UsedPercent', pvePercent(used, total))
        // An unavailable store has no size. It reads 0, not No Data, which would hold a nearly-full
        // alert open; Status alerts for it instead. An available store with no size is withheld.
        def effective = available ? pveEffectiveUsed(store) : 0
        if (effective != null) {
            pveEmit(id, 'EffectiveUsedPercent', effective)
        }
        pveEmit(id, 'Shared', store.shared ? 1 : 0)
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox storage collection failed: ' + exception.message)
    return 2
}
