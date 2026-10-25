package dev.local.miuihdr;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
final class HookConfig {
 final boolean glow,hdrGlow,badge,precision;
 final float captureRatio;
 HookConfig(boolean g,boolean h,boolean b,boolean p){this(g,h,b,p,0);}
 HookConfig(boolean g,boolean h,boolean b,boolean p,float ratio){glow=g;hdrGlow=h;badge=b;precision=p;captureRatio=CaptureRatio.valid(ratio)?ratio:0;}
 static HookConfig read(Context context){try{Bundle p=context.getContentResolver().call(Uri.parse("content://dev.local.miuihdr.options"),"options",null,null);if(p==null)throw new IllegalStateException("No options response");return new HookConfig(p.getBoolean("glow",true),p.getBoolean("hdr_glow",true),p.getBoolean("badge",true),p.getBoolean("pixel_precision",false),p.getBoolean("capture_ratio_auto",true)?0:p.getFloat("capture_ratio",4));}catch(Throwable t){MiuiHook.log("Preferences unavailable; effects skipped: "+t);return new HookConfig(false,false,false,false);}}
}
