#!/usr/bin/env python3
"""Controlled experiment only: re-encode a dumped Android HDR pair.
Metadata comes from the matching Android-produced JPEG, never arbitrary input.
"""
import io,json,re,struct,sys,warnings
from pathlib import Path
from PIL import Image
warnings.filterwarnings('ignore',message='Image appears to be a malformed MPO file')
ROOT=Path(sys.argv[1])
def header(data):
 assert data[:2]==b'\xff\xd8';p=2;segments=[]
 while p<len(data):
  assert data[p]==255;marker=data[p+1]
  if marker==0xda:return segments,data[p:]
  n=int.from_bytes(data[p+2:p+4],'big');assert n>=2
  segments.append((marker,data[p:p+2+n]));p+=2+n
 raise ValueError('No scan')
def segment(marker,payload):return bytes([255,marker])+struct.pack('>H',len(payload)+2)+payload
def encode(raw,desc,keep_rgb=False):
 assert desc['config']=='ARGB_8888'
 im=Image.frombytes('RGBA',(desc['width'],desc['height']),raw,'raw','RGBA',desc['stride']).convert('RGB');buf=io.BytesIO();im.save(buf,format='JPEG',quality=100,subsampling=0,optimize=False,keep_rgb=keep_rgb)
 s,scan=header(buf.getvalue());return b''.join(v for k,v in s if not 0xe0<=k<=0xef)+scan,[(k,v) for k,v in s if k==0xee]
def wrap(meta,encoded,adobe):
 # keep_rgb requires the Adobe RGB transform marker; do not retain old JFIF.
 return b'\xff\xd8'+b''.join(meta)+b''.join(v for k,v in adobe)+encoded

def convert(name,keep_rgb=False):
 template=(ROOT/(name+'.jpg')).read_bytes();off=template.find(b'\xff\xd8\xff',3);assert off>0
 baseHeader,_=header(template[:off]);gainHeader,_=header(template[off:]);j=json.loads((ROOT/(name+'-png.json')).read_text())
 bcode,ba=encode((ROOT/(name+'-png.base')).read_bytes(),j['base'],keep_rgb);gcode,ga=encode((ROOT/(name+'-png.map')).read_bytes(),j['map'],keep_rgb)
 metas=[]
 for hdr in [baseHeader,gainHeader]:metas.append([s for k,s in hdr if 0xe0<=k<=0xef and not (keep_rgb and k in (0xe0,0xee))])
 gain=wrap(metas[1],gcode,ga)
 updated=[]
 for s in metas[0]:
  if s[1]==0xe1 and b'Item:Length=' in s:
   payload,n=re.subn(rb'Item:Length="[0-9]+"',b'Item:Length="'+str(len(gain)).encode()+b'"',s[4:]);assert n==1;s=segment(0xe1,payload)
  updated.append(s)
 base=bytearray(wrap(updated,bcode,ba));mp=base.index(b'MPF\x00')+4
 assert base[mp:mp+4]==b'MM\x00*';ifd=mp+struct.unpack_from('>I',base,mp+4)[0];n=struct.unpack_from('>H',base,ifd)[0];entries=None
 for i in range(n):
  at=ifd+2+12*i;tag,typ,count,val=struct.unpack_from('>HHII',base,at)
  if tag==0xb002:assert count==32;entries=mp+val
 assert entries is not None
 struct.pack_into('>I',base,entries+4,len(base));struct.pack_into('>I',base,entries+8,0)
 struct.pack_into('>I',base,entries+16+4,len(gain));struct.pack_into('>I',base,entries+16+8,len(base)-mp)
 path=ROOT/(name+('-rgb444.jpg' if keep_rgb else '-444.jpg'));path.write_bytes(base+gain)
 for bytes_ in [base,gain]:
  im=Image.open(io.BytesIO(bytes_));assert all(h==1 and v==1 for _,h,v,_ in im.layer);assert all(q==1 for table in im.quantization.values() for q in table)
 print(path.name,len(base)+len(gain))
if __name__=='__main__':
 for name in sys.argv[2:] or ['rebased','p3-rgb-uniform','p3-rgb-mixed']:
  convert(name);convert(name,True)
