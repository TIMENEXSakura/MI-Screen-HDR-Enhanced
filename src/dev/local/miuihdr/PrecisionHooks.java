package dev.local.miuihdr;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import java.io.OutputStream;
import io.github.libxposed.api.XposedInterface;

/** Installed only inside Xiaomi's screenshot process. */
final class PrecisionHooks {
 private static final ThreadLocal<Boolean> ENCODING=new ThreadLocal<>();
 private static volatile Context application;
 static boolean enabled(){Context context=application;return context!=null&&HookConfig.read(context).precision;}
 static Bitmap capture(Bitmap input){return input!=null&&input.hasGainmap()&&enabled()?GainmapPrecision.tryApply(input):input;}
 static void install(XposedInterface api)throws Exception{
  // The package-ready callback runs before Application.attach. Capture the application
  // here instead of calling the removed legacy AndroidAppHelper / hidden APIs.
  api.hook(Application.class.getDeclaredMethod("attach",Context.class)).setId("precision.context").intercept(chain -> {
   Object result=chain.proceed();application=(Application)chain.getThisObject();return result;
  });
  api.hook(Bitmap.class.getDeclaredMethod("compress",Bitmap.CompressFormat.class,int.class,OutputStream.class))
   .setId("precision.jpeg444").setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
    byte[] candidate=null;
    try{
     Bitmap b=(Bitmap)chain.getThisObject();
     if(!Boolean.TRUE.equals(ENCODING.get())&&chain.getArg(0)==Bitmap.CompressFormat.JPEG&&!b.isRecycled()&&b.hasGainmap()&&enabled()){
      try{ENCODING.set(true);long start=System.nanoTime();candidate=HdrJpeg444.encode(b);HdrLog.log("Pixel HDR JPEG 4:4:4 ready bytes="+candidate.length+" ms="+(System.nanoTime()-start)/1000000);}
      finally{ENCODING.remove();}
     }
    }catch(Throwable t){HdrLog.log("Pixel HDR JPEG skipped; original encoder retained: "+t);}
    if(candidate==null)return chain.proceed();
    // PASSTHROUGH is essential: a partial write failure must reach the caller.
    // Protective exception handling would run the original encoder and append
    // a second JPEG to the partially written stream, corrupting the output.
    ((OutputStream)chain.getArg(2)).write(candidate);
    return true;
   });
 }
}
