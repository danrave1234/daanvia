import pathlib,json,valhalla,hashlib
p=pathlib.Path(__file__).resolve().parents[1]/'app/src/main/assets/cebu';meta=json.loads((p/'region.json').read_text())
for name,spec in meta['files'].items():
 assert hashlib.sha256((p/name).read_bytes()).hexdigest()==spec['sha256'],name+' checksum mismatch'
cfg=json.loads((p/'valhalla-template.json').read_text());cfg['mjolnir']['tile_extract']=str(p/'valhalla_tiles.tar');cfg['mjolnir']['tile_dir']='';cfg['mjolnir'].pop('tile_url',None)
actor=valhalla.Actor(cfg)
def route(start,end):return actor.route({'locations':[{'lat':start[0],'lon':start[1]},{'lat':end[0],'lon':end[1]}],'costing':'motor_scooter','language':'en-US','costing_options':{'motor_scooter':{'use_highways':0,'use_tolls':0,'top_speed':80}}})
cases=[('Cebu sample',(10.3157,123.8854),(10.2934,123.9019)),('Moved origin reroute',(10.3176,123.8873),(10.2934,123.9019)),('Reverse route',(10.2934,123.9019),(10.3157,123.8854))]
results=[]
for label,a,b in cases:
 r=route(a,b);trip=r['trip'];assert trip['summary']['length']>0;assert trip['legs'][0]['shape'];assert len(trip['legs'][0]['maneuvers'])>1
 results.append({'test':label,'km':trip['summary']['length'],'maneuvers':len(trip['legs'][0]['maneuvers']),'has_highway':trip['summary']['has_highway'],'has_toll':trip['summary']['has_toll']})
try:route((0,0),(1,1));raise AssertionError('Outside-region route unexpectedly succeeded')
except RuntimeError:results.append({'test':'Outside region rejected','passed':True})
assert all('price_label' not in x for x in json.loads((p/'parking.json').read_text()))
print(json.dumps({'engine':'Valhalla 3.9.0 host build; no remote tile URL','package_checksums':'passed','tests':results,'parking':'No unverified fees bundled'},indent=2))
