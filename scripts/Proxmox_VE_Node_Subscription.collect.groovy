/*
 * Script collection: this node's Proxmox subscription status and expiry.
 *
 * nextduedate is a "YYYY-MM-DD" string, not an epoch, so it is parsed rather than
 * subtracted. A node with no subscription has no due date at all, and reports none --
 * zero days remaining would read as "expires today" on every unlicensed node in the
 * estate.
 */
if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}

def SECONDS_PER_DAY = 86400.0d

try {

    def now = System.currentTimeMillis() / 1000L

    def nodeName = pveSelfNode()
    def subscription = pveNodeGet('/nodes/' + nodeName + '/subscription') ?: [:]
    def status = subscription.status?.toString()?.toLowerCase() ?: ''

    pveEmit(null, 'Active', status == 'active' ? 1 : 0)
    pveEmit(null, 'Sockets', subscription.sockets ?: 0)

    /*
     * checktime is when Proxmox last validated the key against the subscription
     * server. A key that has not been checked in a long time will eventually stop
     * being honoured, so the age is worth watching independently of status.
     */
    if (subscription.checktime != null) {
        pveEmit(null, 'DaysSinceLastCheck',
                pveRound(((now as double) - ((subscription.checktime as double))) /
                         SECONDS_PER_DAY))
    }

    def dueDate = subscription.nextduedate?.toString()
    if (dueDate && dueDate ==~ /^\d{4}-\d{2}-\d{2}$/) {
        // Parsed as UTC midnight; a day either way does not matter at this scale.
        def due = java.time.LocalDate.parse(dueDate)
            .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()
        pveEmit(null, 'DaysUntilDue', pveRound(((due as double) - (now as double)) /
                                            SECONDS_PER_DAY))
    }
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox subscription collection failed: ' + exception.message)
    return 2
}
