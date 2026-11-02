# How LogicMonitor's troubleshooter modules work, and instance auto-delete

Date: 2026-11-01. Read from LogicMonitor's support pages, blog and community site. Claims are
tagged **[doc]** (LogicMonitor documentation or community site, linked) or **[inferred]**.

Purpose: the troubleshooter was added to scope on 2026-10-31 (architecture spec §6). Before its
design (spec §3.2) the user asked whether LogicMonitor's troubleshooters run always or on demand,
and then why the skill's lint refuses instance auto-delete.

## 1. The troubleshooters: always-on DataSources whose instances are problems

| Module | What it is | How a problem shows |
|---|---|---|
| `VMware_LM_Troubleshooter` | "a script-based, Active Discovery enabled datasource that automatically applies to any ESX" resource, checking "various settings to make sure that ESX/vCenter collection will work properly" [doc] | "If it finds an issue, it will report it as an instance and generate an alert" [doc]; an instance reads, for example, "Login failed, verify 'esx.user' and 'esx.pass'. Properties are set, but may be incorrect, or permission may not be correct within vCenter." [doc] |
| Cohesity Troubleshooter | "Checks Cohesity API Credentials" [doc] | "If an instance of the Cohesity Troubleshooter DataSource displays in the Resources tree, there is most likely a logon failure" [doc] |
| SQL Server troubleshooter | checks "servers that appear to be MSSQL servers to see if LM has the credentials necessary to log into that SQL server" [doc] | a "Missing JDBC connection string" instance [doc] |
| Oracle Database Troubleshooter | "exists to capture configuration and permissions issues on initial setup. It is not designed as a monitoring DataSource" [doc] | instances; ongoing login checks are left to `Oracle_Database_ConnectStatus` [doc] |

None is a DiagnosticSource run on demand. A healthy resource has no instances, so the
troubleshooter does not appear under it [doc: a DataSource is listed under a resource only once it
discovers an instance]. How a fixed problem's instance goes away is not documented; it requires
instance auto-delete [inferred].

## 2. Instance auto-delete

LogicMonitor's Active Discovery settings [doc]:

- **Delete Immediately** (`deleteInactiveInstances`): "An instance (and all of its monitoring
  history) is immediately removed from LogicMonitor" once discovery reports it absent.
- **Delete After 30 Days** (*Automatically Delete Instances* on, Delete Immediately off): the instance leaves the Resources tree,
  "its monitoring history will remain in LogicMonitor for 30 days before being removed", and "the
  prior monitoring history will be re-associated upon rediscovery" within that window.
- "Toggle Automatically Delete Instance off if you want to receive alerts for instances even if
  they are not present."
- What happens to an open alert on a deleted instance is not documented. A community thread
  reports that a deleted instance raises nothing more, "the instance doesn't exist any more"
  [doc].

The skill's rule ("never auto-delete", since its first commit) gives as its reason an outage that
makes discovery fail; but a discovery script that exits non-zero keeps its instances (the skill's
own `collector-scripting.md`, [LM recipes]). The real risks for a data module are a successful,
incomplete answer and a deleted instance that can no longer alert. Neither applies to a module
whose instances are findings: there a missing instance means a fixed problem. Spec §3.2 records
the decision this led to.

**Correction (portal, 2026-11-01).** The import reads the export's `autoDeleteInstances`
inverted: `false` ticks *Automatically Delete Instances*, `true` leaves it off (REST
`persistentInstance` takes the value unchanged). An earlier draft of this note mapped
`autoDeleteInstances` to *Delete After 30 Days*; spec §3.2 and §5 have the evidence.

## Sources

- [VMware LM Troubleshooter](https://www.logicmonitor.com/blog/vmware-lm-troubleshooter)
- [Cohesity DataProtect and DataPlatform Monitoring](https://www.logicmonitor.com/support/monitoring/storage-systems/cohesity-dataprotect-and-dataplatform-monitoring)
- [Missing JDBC connection string](https://community.logicmonitor.com/forum/product-discussions-68/topic/missing-jdbc-connection-string-4403/) (community)
- [Oracle Monitoring](https://www.logicmonitor.com/support/monitoring/applications-databases/oracle)
- [How DataSources Get Applied to Resources](https://www.logicmonitor.com/support/devices/adding-managing-devices/how-does-logicmonitor-know-what-to-monitor-for-a-device)
- [Active Discovery](https://www.logicmonitor.com/support/active-discovery)
- [Active Discovery and instance deletion](https://community.logicmonitor.com/discussions/feature-requests/active-discovery-and-instance-deletion/7444) (community)
