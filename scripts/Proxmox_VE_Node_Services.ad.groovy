/*
 * Active Discovery: this node's Proxmox systemd services, one instance per service.
 *
 * /nodes/{node}/services returns the services Proxmox itself manages -- pveproxy,
 * pvedaemon, pve-cluster, corosync and friends -- with their systemd state. Needs
 * Sys.Audit on /nodes/{node}.
 *
 * The node is asked directly, at this resource's own hostname. A node that does not answer
 * fails the run; already-discovered services survive, because deleteInactiveInstances is
 * false.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodeName = pveSelfNode()
    def services = pveNodeGet('/nodes/' + nodeName + '/services') ?: []

    services.each { service ->
        def name = service.name?.toString() ?: service.service?.toString()
        if (!name) { return }

        pveDiscover(
            nodeName + '/' + name,
            nodeName + ' ' + name,
            (service.desc?.toString() ?: name) + ' on ' + nodeName,
            [
                pve_node    : nodeName,
                pve_service : name,
                pve_unit    : service.service,
                pve_desc    : service.desc
            ]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox service discovery failed: ' + exception.message)
    return 2
}
