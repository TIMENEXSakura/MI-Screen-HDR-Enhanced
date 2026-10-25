package dev.local.miuihdr;
import android.graphics.Bitmap;
import android.graphics.Gainmap;
import java.nio.ByteBuffer;
import java.util.WeakHashMap;
final class HdrSignal {
 static final class Info {
  final boolean hdr;final float ratio;final int peak;
  Info(boolean h,float r,int p){hdr=h;ratio=r;peak=p;}
  public String toString(){return "valid="+hdr+" ratio="+ratio+" signal="+peak;}
 }
 private static final WeakHashMap<Bitmap,Info> CACHE=new WeakHashMap<Bitmap,Info>();
 static void invalidate(Bitmap b){synchronized(CACHE){CACHE.remove(b);}}
 static Info inspect(Bitmap b){
  if(b==null||b.isRecycled())return new Info(false,1,0);
  synchronized(CACHE){Info cached=CACHE.get(b);if(cached!=null)return cached;}
  Info result=new Info(false,1,0);Bitmap own=null;
  try{
   if(b.hasGainmap()){
    Gainmap gm=b.getGainmap();float ratio=gm.getDisplayRatioForFullHdr();
    if(Float.isFinite(ratio)&&ratio>1){
     Bitmap map=gm.getGainmapContents();boolean alphaOnly=map.getConfig()==Bitmap.Config.ALPHA_8;
     if(map.getConfig()==Bitmap.Config.HARDWARE){own=map.copy(Bitmap.Config.ARGB_8888,false);map=own;}
     if(map!=null){
      int w=map.getWidth(),h=map.getHeight(),peak=-1;
      if(NativeBridge.loaded)try{peak=NativeBridge.firstSignal(map);}catch(Throwable t){MiuiHook.log("Native signal fallback: "+t);}
      if(peak<0&&(alphaOnly||map.getConfig()==Bitmap.Config.ARGB_8888)){
       // Match Android's raw gainmap shader. getPixels performs color and
       // alpha conversions which can hide a real one-level, one-pixel gain.
       ByteBuffer bytes=ByteBuffer.allocate(map.getByteCount());map.copyPixelsToBuffer(bytes);
       int stride=map.getRowBytes(),channels=alphaOnly?1:4;peak=0;
       search:for(int y=0;y<h;y++)for(int x=0;x<w;x++){
        int at=y*stride+x*channels,value=bytes.get(at)&255;
        if(!alphaOnly)value=Math.max(value,Math.max(bytes.get(at+1)&255,bytes.get(at+2)&255));
        if(value>0){peak=value;break search;}
       }
      }
      if(peak<0){peak=0;int[] row=new int[w];
      search:for(int y=0;y<h;y++){
       map.getPixels(row,0,w,0,y,w,1);
       for(int pixel:row){int value=Math.max((pixel>>>16)&255,Math.max((pixel>>>8)&255,pixel&255));
        peak=Math.max(peak,value);if(peak>0)break search;
       }
      }
      }
      result=new Info(peak>0,ratio,peak);
     }
    }
   }
  }catch(Throwable t){MiuiHook.log("HDR inspection failed: "+t);}
  finally{if(own!=null)own.recycle();}
  synchronized(CACHE){CACHE.put(b,result);}return result;
 }
}
