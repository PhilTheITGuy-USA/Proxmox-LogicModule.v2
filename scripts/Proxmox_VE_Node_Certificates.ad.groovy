/*
 * Active Discovery: this node's TLS certificates, one instance per certificate.
 *
 * Proxmox reports up to three -- the cluster CA, the internal pve-ssl cert, and
 * the pveproxy cert a browser or API client actually sees. They expire independently and
 * on different schedules, so they are discovered separately rather than collapsed into a
 * single soonest-expiry figure: knowing *which* certificate is about to expire is
 * most of the value.
 *
 * The node is asked directly, at this resource's own hostname. A node that does not answer
 * fails the run, and instances already discovered survive, because
 * deleteInactiveInstances is false.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

try {
    def nodeName = pveSelfNode()
    def certificates = pveNodeGet('/nodes/' + nodeName + '/certificates/info') ?: []

    certificates.each { certificate ->
        def filename = certificate.filename?.toString()
        if (!filename) { return }

        // "pveproxy-ssl.pem" -> "pveproxy-ssl", which reads better as an alias and
        // keeps the wildvalue stable if Proxmox ever changes the extension.
        def shortName = filename.replaceAll('\\.pem$', '')

        pveDiscover(
            nodeName + '/' + shortName,
            nodeName + ' ' + shortName,
            'TLS certificate ' + filename + ' on ' + nodeName,
            [
                pve_node     : nodeName,
                pve_cert_file: filename,
                pve_subject  : certificate.subject,
                pve_issuer   : certificate.issuer
            ]
        )
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox certificate discovery failed: ' + exception.message)
    return 2
}
