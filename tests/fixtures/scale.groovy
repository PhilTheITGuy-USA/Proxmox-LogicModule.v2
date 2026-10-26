/*
 * A generated fixture at the design's scale target (spec A§3.9, Q6): one cluster of 16 nodes
 * and 1,000 guests. The harness overlays it on pve_api.json with withFixtures and asserts each
 * script's request count against the budgets in A§3.9.
 *
 * Nodes pve01..pve16 (nodeid 1-16, 10.1.0.1-16, all online, pve01 local); guests 1000-1999,
 * qemu and lxc alternating, spread round-robin over the nodes, each with a NIC and no
 * snapshots but "current".
 */
def cluster = 'pve-scale'
def nodes = (1..16).collect { i ->
    [name: String.format('pve%02d', i), nodeid: i, ip: '10.1.0.' + i]
}
def guests = (0..<1000).collect { i ->
    def node = nodes[i % nodes.size()].name
    def type = i % 2 == 0 ? 'qemu' : 'lxc'
    def vmid = 1000 + i
    [id: type + '/' + vmid, type: type, vmid: vmid, name: type + '-' + vmid, node: node, status: 'running',
     template: 0, cpu: 0.05, maxcpu: 2, mem: 1073741824, maxmem: 2147483648, maxdisk: 34359738368,
     disk: 0, netin: 1000, netout: 1000, diskread: 1000, diskwrite: 1000, uptime: 3600]
}

def fixtures = [:]
fixtures['/cluster/status'] = [[type: 'cluster', id: 'cluster', name: cluster, nodes: 16, quorate: 1, version: 16]] +
    nodes.collect { [type: 'node', id: 'node/' + it.name, name: it.name, nodeid: it.nodeid, ip: it.ip, online: 1,
                     local: it.nodeid == 1 ? 1 : 0] }
def nodeResources = nodes.collect {
    [id: 'node/' + it.name, type: 'node', node: it.name, status: 'online', cpu: 0.1, maxcpu: 32,
     mem: 68719476736, maxmem: 274877906944, disk: 10737418240, maxdisk: 107374182400, uptime: 604800]
}
fixtures['/cluster/resources?type=node'] = nodeResources
fixtures['/cluster/resources?type=vm'] = guests
fixtures['/cluster/resources'] = nodeResources + guests
guests.each { g ->
    def mac = String.format('BC:24:11:%02X:%02X:%02X', (g.vmid >> 16) & 0xff, (g.vmid >> 8) & 0xff, g.vmid & 0xff)
    def base = '/nodes/' + g.node + '/' + g.type + '/' + g.vmid
    fixtures[base + '/config'] = g.type == 'qemu' ?
        [name: g.name, net0: 'virtio=' + mac + ',bridge=vmbr0'] :
        [hostname: g.name, net0: 'name=eth0,bridge=vmbr0,hwaddr=' + mac + ',type=veth']
    fixtures[base + '/snapshot'] = [[name: 'current', description: 'You are here!']]
}
nodes.each { n ->
    fixtures['/nodes/' + n.name + '/rrddata?timeframe=hour&cf=AVERAGE'] =
        [[time: 1792965660, cpu: 0.1, iowait: 0.004, loadavg: 2.0, maxcpu: 32,
          pressurecpusome: 1.0, pressureiosome: 0.5, pressureiofull: 0.2, pressurememorysome: 0, pressurememoryfull: 0]]
    fixtures['/nodes/' + n.name + '/tasks?typefilter=qmigrate&limit=100'] = []
    fixtures['/nodes/' + n.name + '/tasks?typefilter=vzmigrate&limit=100'] = []
}
return fixtures
