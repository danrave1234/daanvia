import osmium,json,pathlib,sys,datetime
src=sys.argv[1];out=pathlib.Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
# The extract includes the metropolitan area and some approach roads.
bounds=(123.60,10.10,124.08,10.62)
nodes={};ways=[];relations=[];places=[];parking=[];needed=set()
def inside(lon,lat):return bounds[0]<=lon<=bounds[2] and bounds[1]<=lat<=bounds[3]
class Scan(osmium.SimpleHandler):
 def node(self,n):
  if not n.location.valid() or not inside(n.location.lon,n.location.lat):return
  nodes[n.id]=(n.location.lon,n.location.lat)
  t=dict(n.tags)
  if t.get('amenity') in ('parking','motorcycle_parking'):
   parking.append({'id':str(n.id),'lon':n.location.lon,'lat':n.location.lat,'name':t.get('name','Parking'),'source':'OpenStreetMap','motorcycle':t.get('amenity')=='motorcycle_parking','access':t.get('access','unknown')})
  if t.get('name') and any(k in t for k in ('amenity','tourism','shop','place','public_transport')):
   places.append({'name':t['name'],'lon':n.location.lon,'lat':n.location.lat})
 def way(self,w):
  ids=[];pts=[];t=dict(w.tags)
  if not ('highway' in t or t.get('amenity') in ('parking','motorcycle_parking') or t.get('natural')=='water'):return
  try:
   for n in w.nodes:ids.append(n.ref);pts.append((n.location.lon,n.location.lat))
  except osmium.InvalidLocationError:return
  if not any(inside(x,y) for x,y in pts):return
  ways.append((w.id,ids,t));needed.update(ids)
  for nid,xy in zip(ids,pts):nodes[nid]=xy
 def relation(self,r):
  if r.tags.get('type')=='restriction':relations.append((r.id,[(m.type,m.ref,m.role) for m in r.members],dict(r.tags)))
print('Scanning OSM with native tag filtering...',flush=True);Scan().apply_file(src,locations=True,idx='sparse_mem_array',filters=[osmium.filter.KeyFilter('highway','amenity','natural','name','type')])
coords={k:v for k,v in nodes.items() if k in needed};writer=osmium.SimpleWriter(str(out/'cebu.osm.pbf'),overwrite=True)
print('Writing region nodes...',flush=True)
for nid,xy in coords.items():writer.add_node(osmium.osm.mutable.Node(id=nid,location=xy))
way_ids={w[0] for w in ways}
features=[]
for wid,ids,t in ways:
 if any(i not in coords for i in ids):continue
 writer.add_way(osmium.osm.mutable.Way(id=wid,nodes=ids,tags=t))
 pts=[coords[n] for n in ids]
 if 'highway' in t:
  features.append({'type':'Feature','properties':{'name':t.get('name',''),'kind':t['highway']},'geometry':{'type':'LineString','coordinates':pts}})
 if t.get('amenity') in ('parking','motorcycle_parking') and pts:
  parking.append({'id':str(wid),'lon':sum(x[0] for x in pts)/len(pts),'lat':sum(x[1] for x in pts)/len(pts),'name':t.get('name','Parking'),'source':'OpenStreetMap','motorcycle':t.get('amenity')=='motorcycle_parking','access':t.get('access','unknown')})
 if t.get('name') and pts:
  places.append({'name':t['name'],'lon':pts[len(pts)//2][0],'lat':pts[len(pts)//2][1]})
for rid,members,t in relations:
 if any(typ=='w' and ref in way_ids for typ,ref,role in members):
  if all((typ!='w' or ref in way_ids) and (typ!='n' or ref in coords) for typ,ref,role in members):
   writer.add_relation(osmium.osm.mutable.Relation(id=rid,members=members,tags=t))
writer.close()
(out/'roads.geojson').write_text(json.dumps({'type':'FeatureCollection','features':features},separators=(',',':')))
# Unreviewed/general/private parking must not imply motorcycle eligibility or public access.
parking=[x for x in parking if x['access'] not in ('private','no','permit')]
(out/'places.json').write_text(json.dumps(places,separators=(',',':')))
(out/'parking.json').write_text(json.dumps(parking,separators=(',',':')))
(out/'region.json').write_text(json.dumps({'id':'cebu-metro-alpha','name':'Metro Cebu alpha','bounds':bounds,'built':datetime.datetime.now(datetime.timezone.utc).isoformat(),'source':'OpenStreetMap / Geofabrik','license':'ODbL-1.0','attribution':'© OpenStreetMap contributors','roads':len(features),'places':len(places),'parking':len(parking),'note':'Prices not verified; unknown fees omitted. Route access requires field review.'},indent=2))
print('Region ready:',len(features),'roads,',len(places),'places,',len(parking),'parking',flush=True)
