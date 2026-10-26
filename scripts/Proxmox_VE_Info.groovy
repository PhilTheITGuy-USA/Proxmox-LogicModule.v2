/*
 * PropertySource: version facts for a Proxmox VE cluster or node resource.
 *
 * Applies only to resources that already carry a Proxmox role category, which whoever created
 * the resource set (the NetScan, or a user adding it by hand). It sets no category itself:
 * categories mark what a resource is, and nothing here decides that.
 *
 * Emits nothing at all when credentials are absent or the API does not answer.
 */
if (pveConfigError) {
    // Not an error: most resources in a portal are not Proxmox and have no token set.
    return 0
}

try {
    // /version is the cheapest authenticated endpoint that proves this is really Proxmox VE.
    def version = pveGet('/version')
    if (!version || !version.version) {
        return 0
    }

    // LogicMonitor stores every property a PropertySource sets, except system.categories, with
    // auto. prepended: these land as auto.pve.version, auto.pve.release and auto.pve.clustered.
    // Reference them by those names in AppliesTo and docs; the output itself stays bare.
    println 'pve.version=' + version.version
    if (version.release) { println 'pve.release=' + version.release }

    // Presence of a cluster entry distinguishes a cluster member from a standalone host,
    // which lets AppliesTo target cluster-only modules without a second probe.
    try {
        def clusterRow = (pveGet('/cluster/status') ?: []).find { it.type == 'cluster' }
        println 'pve.clustered=' + (clusterRow != null)
        // The exact name Proxmox_VE_Cluster_Topology keys the cluster on, published for the optional
        // pve.topology.cluster; the name-plus-IP node key needs no cluster name.
        if (clusterRow?.name) { println 'pve.cluster.name=' + clusterRow.name }
    } catch (Exception clusterException) {
        System.err.println('Could not determine cluster membership: ' + clusterException.message)
    }

    return 0
} catch (Exception exception) {
    System.err.println('Proxmox detection failed: ' + exception.message)
    return 0
}
