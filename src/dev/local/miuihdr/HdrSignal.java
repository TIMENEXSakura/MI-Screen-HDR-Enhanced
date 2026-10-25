package dev.local.miuihdr;
import android.graphics.Bitmap;
import android.graphics.Gainmap;
import java.util.WeakHashMap;
final class HdrSignal {
 static final class Info {
  final boolean hdr;final float ratio;final int peak;
  Info(boolean h,float r,int p){hdr=h;ratio=r;peak=p;}
  public String toString(){return "valid="+hdr+" ratio="+ratio+" signal="+peak;}
 }
 private static final WeakHashMap<Bitmap,Info> CACHE=new WeakHashMap<Bitmap,Info>();
 static Info inspect(Bitmap b){
  if(b==null||b.isRecycled())return new Info(false,1,0);
  synchronized(CACHE){Info cached=CACHE.get(b);if(cached!=null)return cached;}
  Info result=new Info(false,1,0);Bitmap own=null;
  try{
   if(b.hasGainmap()){
    Gainmap gm=b.getGainmap();float ratio=gm.getDisplayRatioForFullHdr();
    if(Float.isFinite(ratio)&&ratio>1.01f){
     Bitmap map=gm.getGainmapContents();boolean alphaOnly=map.getConfig()==Bitmap.Config.ALPHA_8;
     if(map.getConfig()==Bitmap.Config.HARDWARE){own=map.copy(Bitmap.Config.ARGB_8888,false);map=own;}
     if(map!=null){
      int w=map.getWidth(),h=map.getHeight(),peak=-1;
      if(NativeBridge.loaded)try{peak=NativeBridge.firstSignal(map);}catch(Throwable t){HdrLog.log("Native signal fallback: "+t);}
      if(peak<0){peak=0;int[] row=new int[w];
      search:for(int y=0;y<h;y++){
       map.getPixels(row,0,w,0,y,w,1);
       for(int pixel:row){int alpha=pixel>>>24;int value=alphaOnly?alpha:Math.max((pixel>>>16)&255,Math.max((pixel>>>8)&255,pixel&255))*alpha/255;
        peak=Math.max(peak,value);if(peak>1)break search;
       }
      }
      }
      result=new Info(peak>1,ratio,peak);
     }
    }
   }
  }catch(Throwable t){HdrLog.log("HDR inspection failed: "+t);}
  finally{if(own!=null)own.recycle();}
  synchronized(CACHE){CACHE.put(b,result);}return result;
 }
}
