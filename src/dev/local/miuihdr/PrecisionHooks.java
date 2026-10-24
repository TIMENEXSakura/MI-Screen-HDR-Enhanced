package dev.local.miuihdr;
import android.app.AndroidAppHelper;
import android.content.Context;
import android.graphics.Bitmap;
import java.io.OutputStream;
import de.robv.android.xposed.*;

/** Installed only inside Xiaomi's screenshot process. */
final class PrecisionHooks {
 private static final ThreadLocal<Boolean> ENCODING=new ThreadLocal<>();
 static boolean enabled(){Context context=AndroidAppHelper.currentApplication();return context!=null&&HookConfig.read(context).precision;}
 static Bitmap capture(Bitmap input){return input!=null&&input.hasGainmap()&&enabled()?GainmapPrecision.tryApply(input):input;}
 static void install()throws Exception{
  XposedBridge.hookMethod(Bitmap.class.getDeclaredMethod("compress",Bitmap.CompressFormat.class,int.class,OutputStream.class),new XC_MethodHook(){
   protected void beforeHookedMethod(MethodHookParam param){
    if(Boolean.TRUE.equals(ENCODING.get())||param.args[0]!=Bitmap.CompressFormat.JPEG)return;
    Bitmap b=(Bitmap)param.thisObject;if(b.isRecycled()||!b.hasGainmap()||!enabled())return;
    byte[] candidate;
    try{ENCODING.set(true);long start=System.nanoTime();candidate=HdrJpeg444.encode(b);MiuiHook.log("Pixel HDR JPEG 4:4:4 ready bytes="+candidate.length+" ms="+(System.nanoTime()-start)/1000000);}
    catch(Throwable t){MiuiHook.log("Pixel HDR JPEG skipped; original encoder retained: "+t);return;}
    finally{ENCODING.remove();}
    // Only fall back before writing. An I/O failure must not append a second
    // JPEG after a partial candidate and make the save appear successful.
    try{((OutputStream)param.args[2]).write(candidate);param.setResult(true);}
    catch(Throwable t){param.setThrowable(t);}
   }
  });
 }
}
