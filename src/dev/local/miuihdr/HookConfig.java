package dev.local.miuihdr;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
final class HookConfig {
 final boolean glow,hdrGlow,badge,precision;
 final float captureRatio,captureRatioOffset;
 final String captureRatioMode;
 HookConfig(boolean g,boolean h,boolean b,boolean p){this(g,h,b,p,CaptureRatio.AUTO,0,4);}
 HookConfig(boolean g,boolean h,boolean b,boolean p,float ratio){this(g,h,b,p,CaptureRatio.validForced(ratio)?CaptureRatio.FORCE:CaptureRatio.AUTO,0,ratio);}
 HookConfig(boolean g,boolean h,boolean b,boolean p,String mode,float offset,float ratio){
  glow=g;hdrGlow=h;badge=b;precision=p;
  captureRatioMode=CaptureRatio.OFFSET.equals(mode)?CaptureRatio.OFFSET:CaptureRatio.FORCE.equals(mode)?CaptureRatio.FORCE:CaptureRatio.AUTO;
  captureRatioOffset=offset;captureRatio=ratio;
 }
 static HookConfig read(Context context){try{
  Bundle p=context.getContentResolver().call(Uri.parse("content://dev.local.miuihdr.options"),"options",null,null);
  if(p==null)throw new IllegalStateException("No options response");
  String mode=p.getBoolean("capture_ratio_auto",true)?CaptureRatio.AUTO:p.getString("capture_ratio_mode",CaptureRatio.FORCE);
  return new HookConfig(p.getBoolean("glow",true),p.getBoolean("hdr_glow",true),p.getBoolean("badge",true),p.getBoolean("pixel_precision",false),mode,p.getFloat("capture_ratio_offset",0),p.getFloat("capture_ratio",4));
 }catch(Throwable t){MiuiHook.log("Preferences unavailable; effects skipped: "+t);return new HookConfig(false,false,false,false);}}
}
