/*
 * BatchScript collection: node headline metrics, cluster-wide, in one API call.
 * Load average, swap and root filesystem live in Proxmox_VE_Node_Detail because they
 * require a per-node call that /cluster/resources cannot serve.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}
// Only the resource that holds the cluster role now collects; the other prints nothing.
if (!pveRole('Proxmox_VE_Cluster_Nodes').collect) {
    return 0
}

try {
    def nodes = pveGet('/cluster/resources?type=node') ?: []

    nodes.each { node ->
        def id = node.id
        def online = node.status?.toString() == 'online'

        // Only whether each node is online: a node that is down cannot report itself down.
        // Its CPU, memory and uptime are collected on the node's own resource.
        pveEmit(id, 'Status', online ? 1 : 0)
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox node collection failed: ' + exception.message)
    return 2
}
