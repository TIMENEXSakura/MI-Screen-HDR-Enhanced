package dev.local.miuihdr;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
final class HookConfig {
 final boolean glow,hdrGlow,badge,precision;
 HookConfig(boolean g,boolean h,boolean b,boolean p){glow=g;hdrGlow=h;badge=b;precision=p;}
 static HookConfig read(Context context){try{Bundle p=context.getContentResolver().call(Uri.parse("content://dev.local.miuihdr.options"),"options",null,null);if(p==null)throw new IllegalStateException("No options response");return new HookConfig(p.getBoolean("glow",true),p.getBoolean("hdr_glow",true),p.getBoolean("badge",true),p.getBoolean("pixel_precision",false));}catch(Throwable t){HdrLog.log("Preferences unavailable; effects skipped: "+t);return new HookConfig(false,false,false,false);}}
}
