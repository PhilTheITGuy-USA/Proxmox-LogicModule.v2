/*
 * Active Discovery: one instance per problem that stops this resource's collection, named
 * with its fix, and none while all is well (pveTroubleshoot runs the checks).
 *
 * A missing token or URL is the settings finding here, not a failure: the troubleshooter
 * exists to say so. A check that cannot run is a finding too, so an instance this discovery
 * leaves out is a problem fixed, which is why this module alone auto-deletes (after 30 days).
 * Anything else that goes wrong exits 2, so the instances already discovered stay.
 */
try {
    pveTroubleshoot(pveTroubleProbes()).findings.each { finding ->
        pveDiscover(finding.id, finding.name, finding.finding, [pve_finding: finding.finding, pve_check: finding.id])
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox troubleshooter discovery failed: ' + exception.message)
    return 2
}
