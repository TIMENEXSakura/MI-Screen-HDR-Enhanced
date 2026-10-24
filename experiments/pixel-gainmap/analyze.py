#!/usr/bin/env python3
"""Independent radiance/occupancy audit of device dumps. Requires NumPy."""
import json,sys
from pathlib import Path
import numpy as np
ROOT=Path(sys.argv[1])
def load(name):
 p=ROOT/name;j=json.loads(Path(str(p)+'.json').read_text())
 def raw(k):
  v=j[k];c=1 if v['config']=='ALPHA_8' else 4
  assert v['config'] in ('ALPHA_8','ARGB_8888')
  a=np.fromfile(str(p)+('.base' if k=='base' else '.map'),dtype=np.uint8)
  return a.reshape(v['height'],v['stride'])[:,:v['width']*c].reshape(v['height'],v['width'],c)[...,:3]/255.
 b=raw('base');g=raw('map');lin=np.where(b<=.04045,b/12.92,((b+.055)/1.055)**2.4)
 assert b.shape[:2]==g.shape[:2]
 h=(lin+j['epsSdr'])*np.exp(np.log(j['min'])+(np.log(j['max'])-np.log(j['min']))*g**np.array(j['gamma']))-j['epsHdr']
 return dict(meta=j,base=b,gain=g,hdr=h,positive=np.any(g>0,axis=-1))
def stats(d,ref,mask):
 err=np.abs(d['hdr']-ref['hdr'])/np.maximum(.02,np.abs(ref['hdr']))
 # Linear Display P3 luminance; sRGB has different primaries.
 p3='Display P3' in d['meta']['base']['space'];w=np.array([.228974564,.691738522,.079286914] if p3 else [.2126,.7152,.0722])
 lum=d['hdr']@w;refLum=ref['hdr']@w;le=np.abs(lum-refLum)/np.maximum(.02,refLum)
 return {'nonzero_gain_pixels':int(d['positive'].sum()),'extra_gain_pixels':int((d['positive']&~mask).sum()),'missing_gain_pixels':int((~d['positive']&mask).sum()),'extra_gain_max_byte':float(d['gain'][~mask].max()*255),'hdr_channel_error_max_percent':float(err[mask].max()*100),'hdr_luminance_error_max_percent':float(le[mask].max()*100),'hdr_luminance_error_mean_percent':float(le[mask].mean()*100),'sdr_error_max_percent':float(err[~mask].max()*100)}
report={}
for p in sorted(ROOT.glob('*-input.json')):
 name=p.name[:-11]
 if name=='white-ramp':continue
 data={n:load(name+'-'+n) for n in ['input','memory','png','jpg']}
 mask=(np.max(load('rebased-source')['hdr'],axis=-1)>1) if name=='rebased' else np.max(data['input']['hdr'],axis=-1)>1.01
 expected=5881 if name=='rebased' else 516
 assert int(mask.sum())==expected,(name,'fixture changed')
 r={n:stats(d,data['input'],mask) for n,d in data.items()}
 for n in ['memory','png']:
  assert r[n]['extra_gain_pixels']==r[n]['missing_gain_pixels']==0,(name,n)
  assert r[n]['hdr_channel_error_max_percent']<.5,(name,n)
 # Byte and metadata equality independently establish lossless PNG roundtrip.
 assert np.array_equal(data['png']['base'],data['memory']['base'])
 assert np.array_equal(data['png']['gain'],data['memory']['gain'])
 assert np.allclose(data['png']['hdr'],data['memory']['hdr'],atol=1e-6,rtol=.0002)
 for n in ['repeat','repeat10','444','rgb444','original-jpg']:
  if (ROOT/(name+'-'+n+'.json')).exists():
   d=load(name+'-'+n);r[n]=stats(d,data['memory'] if n.startswith('repeat') else data['png'],mask)
   if n.startswith('repeat'):assert r[n]['hdr_channel_error_max_percent']<.001,(name,n)
 r['error_reference']='input for input/memory/png/jpg; memory for repeats; PNG for 444, rgb444, original-jpg'
 report[name]=r
if (ROOT/'white-ramp-input.json').exists():
 a=load('white-ramp-input');b=load('white-ramp-memory');weak=(np.max(a['hdr'],axis=-1)>1)&~b['positive']
 assert not np.any(b['positive']&(np.max(a['hdr'],axis=-1)<=1))
 report['white_boundary']={'omitted_positive_pairs':int(weak.sum()),'strongest_omitted_radiance':float(np.max(a['hdr'][weak]))}
(ROOT/'audit.json').write_text(json.dumps(report,indent=2)+'\n')
print('PASS pixel occupancy, HDR radiance, PNG roundtrip and repeated processing;',len(report),'cases')
