import osmium,sys
nodes=[];ways=[];relations=[]
class Read(osmium.SimpleHandler):
 def node(self,n):nodes.append((n.id,n.location.lon,n.location.lat,dict(n.tags)))
 def way(self,w):ways.append((w.id,[n.ref for n in w.nodes],dict(w.tags)))
 def relation(self,r):relations.append((r.id,[(m.type,m.ref,m.role) for m in r.members],dict(r.tags)))
Read().apply_file(sys.argv[1])
w=osmium.SimpleWriter(sys.argv[2],overwrite=True)
for i,x,y,t in sorted(nodes):w.add_node(osmium.osm.mutable.Node(id=i,location=(x,y),tags=t))
for i,n,t in sorted(ways):w.add_way(osmium.osm.mutable.Way(id=i,nodes=n,tags=t))
for i,m,t in sorted(relations):w.add_relation(osmium.osm.mutable.Relation(id=i,members=m,tags=t))
w.close();print('Sorted',len(nodes),'nodes,',len(ways),'ways,',len(relations),'restrictions')
