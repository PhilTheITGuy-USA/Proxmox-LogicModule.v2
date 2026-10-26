/*
 * Active Discovery: Proxmox VE cluster nodes.
 * Used by Proxmox_VE_Cluster_Nodes only: every node, online or not, since a node that is
 * down is exactly what it reports.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodes = pveGet('/cluster/resources?type=node') ?: []

    if (nodes.isEmpty()) {
        System.err.println('/cluster/resources returned no nodes. Verify the API token has an ACL ' +
            'granting at least PVEAuditor.')
    }

    nodes.each { node ->
        def rootfs = pveSizeLabel(node.maxdisk)
        pveDiscover(
            node.id,
            node.node,
            'Proxmox VE node ' + node.node + (rootfs ? ', root filesystem ' + rootfs : ''),
            [pve_id: node.id, pve_node: node.node]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox node discovery failed: ' + exception.message)
    return 2
}
