package dev.local.miuihdr;
import android.graphics.Bitmap;
import android.graphics.Gainmap;
import java.util.WeakHashMap;
final class HdrSignal {
 // Describes only Android's standard gainmap. Xiaomi window/renderer HDR
 // can be active independently; identity metadata is not a whole-image verdict.
 static boolean neutralStandardMetadata(Gainmap gm){
  float[] min=gm.getRatioMin(),max=gm.getRatioMax(),sdr=gm.getEpsilonSdr(),hdr=gm.getEpsilonHdr();
  for(int c=0;c<3;c++)if(min[c]!=1||max[c]!=1||sdr[c]!=hdr[c])return false;
  return true;
 }
 static final class Info {
  final boolean hdr;final float ratio;final int peak;final String state;
  Info(boolean h,float r,int p){this(h,r,p,h?"active":"absent");}
  Info(boolean h,float r,int p,String s){hdr=h;ratio=r;peak=p;state=s;}
  public String toString(){return "valid="+hdr+" ratio="+ratio+" signal="+peak+" state="+state;}
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
    float[] minimum=gm.getRatioMin(),maximum=gm.getRatioMax(),sdr=gm.getEpsilonSdr(),hdr=gm.getEpsilonHdr();
    boolean identity=neutralStandardMetadata(gm),constantBoost=true;
    for(int c=0;c<3;c++){
     constantBoost&=minimum[c]>1.01f&&maximum[c]>=minimum[c]&&sdr[c]>=hdr[c];
    }
    // A real second JPEG can have neutral XMP/ISO metadata (e.g. Leica M9).
    // Its nonzero pixels must not be promoted to HDR by inventing a ratio.
    if(identity){result=new Info(false,ratio,0,"neutral_metadata");}
    else if(!Float.isFinite(ratio)||ratio<=1.01f){result=new Info(false,ratio,0,"no_hdr_headroom");}
    else if(constantBoost){result=new Info(true,ratio,0,"constant_gain");}
    else {
    if(Float.isFinite(ratio)&&ratio>1.01f){
     Bitmap map=gm.getGainmapContents();boolean alphaOnly=map.getConfig()==Bitmap.Config.ALPHA_8;
     if(map.getConfig()==Bitmap.Config.HARDWARE){own=map.copy(Bitmap.Config.ARGB_8888,false);map=own;}
     if(map!=null){
      int w=map.getWidth(),h=map.getHeight(),peak=-1;
      if(NativeBridge.loaded)try{peak=NativeBridge.firstSignal(map);}catch(Throwable t){MiuiHook.log("Native signal fallback: "+t);}
      if(peak<0){peak=0;int[] row=new int[w];
      search:for(int y=0;y<h;y++){
       map.getPixels(row,0,w,0,y,w,1);
       for(int pixel:row){int alpha=pixel>>>24;int value=alphaOnly?alpha:Math.max((pixel>>>16)&255,Math.max((pixel>>>8)&255,pixel&255))*alpha/255;
        peak=Math.max(peak,value);if(peak>1)break search;
       }
      }
      }
      result=new Info(peak>1,ratio,peak,peak>1?"active":"empty_signal");
     }
    }
    }
   }
  }catch(Throwable t){MiuiHook.log("HDR inspection failed: "+t);}
  finally{if(own!=null)own.recycle();}
  synchronized(CACHE){CACHE.put(b,result);}return result;
 }
}
