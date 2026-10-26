/*
 * Active Discovery: this node's physical disks, one instance per disk.
 *
 * /nodes/{node}/disks/list needs Sys.Audit on / or on /nodes/{node}. Partitions are not
 * requested (include-partitions defaults to 0): a partition's SMART health is the health
 * of the disk beneath it, so discovering both would duplicate every instance.
 *
 * The wildvalue drops the /dev/ prefix -- "pve1/sda" rather than "pve1//dev/sda" -- which
 * keeps it readable once the character fold has run.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodeName = pveSelfNode()
    def disks = pveNodeGet('/nodes/' + nodeName + '/disks/list') ?: []

    disks.each { disk ->
        def devpath = disk.devpath?.toString()
        if (!devpath) { return }

        def shortPath = devpath.replaceAll('^/dev/', '')
        def model = disk.model?.toString()?.trim()

        pveDiscover(
            nodeName + '/' + shortPath,
            nodeName + ' ' + shortPath,
            ((model ?: 'Disk') + ' on ' + nodeName + ' at ' + devpath),
            [
                pve_node   : nodeName,
                pve_devpath: devpath,
                pve_model  : model,
                pve_serial : disk.serial,
                pve_vendor : disk.vendor,
                pve_type   : disk.type
            ]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox disk discovery failed: ' + exception.message)
    return 2
}
