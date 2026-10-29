/*
 * TopologySource: the Proxmox VE map, modelled on LogicMonitor's vSphere suite.
 *
 *   Manage    cluster -> node
 *   Compute   node -> guest
 *   Storage   node -> store, guest -> store, store -> pool -> disk, node -> Ceph OSD -> disk,
 *             and node -> guest for a host path or device no store covers
 *   Network   node -> bridge; bridge or SDN VNet -> guest (ports, bonds, VLANs kept off the map)
 *
 * THIS SCRIPT CANNOT BE COMPILED OR RUN OUTSIDE A COLLECTOR. Every LogicMonitor
 * TopologySource works through com.logicmonitor.mod.Snippets: lmTopo.eriPreProcessor normalises
 * keys and drops the blocked ones, and lmTopo.generateTopology emits the output format. So
 * everything Proxmox-side lives in the preamble: pveTopoInput does the reads and pveTopoPlan
 * decides every edge, and the harness tests both. This script only turns keys into ERIs.
 *
 * A vertex is drawn only where an instance or resource carries its key:
 *
 *   cluster   Proxmox_VE_Cluster_Status's instance, HyperVisorCluster
 *   node      the node's resource, through addERI_Proxmox_VE_Node, HyperVisor. One key per
 *             node vertex: lm.topo joins a vertex's keys into one ID, and the portal then
 *             matches only a resource carrying every one of them.
 *   guest     the MAC of its first NIC: Proxmox_VE_Cluster_GuestStatus's instance, and the
 *             guest's own resource where it has one
 *   store     Proxmox_VE_Cluster_StorageCapacity's instances, StorageVolume (VMDatastore takes no edges)
 *   pool      Proxmox_VE_Node_StoragePools (ZFS, LVM, LVM-thin) and
 *             Proxmox_VE_Cluster_CephPools, StorageAggregate
 *   OSD       Proxmox_VE_Cluster_CephOSD, StorageNode; disk  Proxmox_VE_Node_Disks, PhysicalDisk
 *   bridge    Proxmox_VE_Node_Network, VirtualSwitch; SDN  Proxmox_VE_Cluster_SDN, VirtualSwitch
 */
import com.logicmonitor.mod.Snippets
import com.santaba.agent.groovy.utils.GroovyScriptHelper as GSH

if (pveConfigError) {
    System.err.println(pveConfigError)
    return 2
}
// Only the resource that holds the cluster role now collects; the other prints nothing.
if (!pveRole('Proxmox_VE_Cluster_Topology').collect) {
    return 0
}

def debug = false

// Namespacing and the key blacklist are host properties LogicMonitor's own topology
// scripts honour; a portal with duplicate keys across environments relies on them.
def keyNamespace = pveHostProp('topo.namespace') ? pveHostProp(pveHostProp('topo.namespace')) : ''
def keyBlacklist = (pveHostProp('topo.blacklist') ?: '').tokenize(',')

def modLoader = GSH.getInstance(GroovySystem.version)
    .getScript('Snippets', Snippets.getLoader()).withBinding(getBinding())
def lmTopo = modLoader.load('lm.topo', '0')
def lmTopoData = modLoader.load('lm.data.topo', '1').create()

def eris = [:]
def eri = { String key ->
    eris.computeIfAbsent(key) { lmTopo.eriPreProcessor([key], lmTopoData.blockedKeys, keyNamespace, keyBlacklist) }
}

try {
    def edges = []
    pveTopoPlan(pveTopoInput()).each { type, from, to ->
        // A key the blacklist or lm.data.topo's blocked list removes leaves no vertex to join.
        def source = eri(from)
        def target = eri(to)
        if (source && target) { lmTopo.registerEdge(type, source, target, edges) }
    }
    println lmTopo.generateTopology(edges, keyNamespace, keyBlacklist, null, debug)
    return 0
} catch (Exception exception) {
    System.err.println('Proxmox topology collection failed: ' + exception.message)
    return 2
}
