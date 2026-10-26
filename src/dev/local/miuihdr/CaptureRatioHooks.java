package dev.local.miuihdr;
import dev.local.miuihdr.HookRuntime.Callback;
import dev.local.miuihdr.HookRuntime.Call;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.view.Display;
import java.lang.reflect.*;

/** Thread-local capture context, so simultaneous/rear captures cannot reuse ratios. */
final class CaptureRatioHooks {
 private static final ThreadLocal<Sample> ACTIVE=new ThreadLocal<>();
 private static final class Sample {
  final float display,offset,forced;final String mode;
  Sample(float d,HookConfig c){display=d;mode=c.captureRatioMode;offset=c.captureRatioOffset;forced=c.captureRatio;}
 }
 static void begin(Context context,int displayId){
  float ratio=1;HookConfig config=HookConfig.read(context);
  try{Display display=((DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE)).getDisplay(displayId);
   if(display!=null&&display.isHdrSdrRatioAvailable())ratio=display.getHdrSdrRatio();
  }catch(Throwable t){MiuiHook.log("Current display ratio unavailable: "+t);}
  ACTIVE.set(new Sample(ratio,config));
  MiuiHook.log("Capture brightness display="+displayId+" current="+ratio+" mode="+config.captureRatioMode+" offset="+config.captureRatioOffset+" forced="+config.captureRatio);
 }
 static void end(){ACTIVE.remove();}
 static void install(){
  for(String name:new String[]{"android.window.ScreenCaptureInternal$ScreenshotHardwareBuffer","android.window.ScreenCapture$ScreenshotHardwareBuffer"})try{
   Class<?> type=Class.forName(name);Field nativeRatio=type.getDeclaredField("mHdrSdrRatio");nativeRatio.setAccessible(true);
   Method hasHdr=type.getDeclaredMethod("containsHdrLayers"),secure=type.getDeclaredMethod("containsSecureLayers");
   HookRuntime.add(type.getDeclaredMethod("asBitmap"),new Callback(){protected void after(Call p){
    Sample sample=ACTIVE.get();if(sample==null||p.hasThrowable()||!(p.getResult() instanceof Bitmap))return;
    try{
     if(Boolean.TRUE.equals(secure.invoke(p.thisObject)))return;
     Bitmap bitmap=HdrCaptureHooks.process(p.thisObject,(Bitmap)p.getResult());
     if(bitmap!=p.getResult())p.setResult(bitmap);
     if(bitmap==null||(!Boolean.TRUE.equals(hasHdr.invoke(p.thisObject))&&!bitmap.hasGainmap()))return;
     float original=HdrCaptureImage.created(bitmap)&&bitmap.hasGainmap()?bitmap.getGainmap().getDisplayRatioForFullHdr():nativeRatio.getFloat(p.thisObject);
     if(CaptureRatio.apply(bitmap,original,sample.display,sample.mode,sample.offset,sample.forced))MiuiHook.log("Capture gainmap ratio corrected "+original+" -> "+bitmap.getGainmap().getDisplayRatioForFullHdr()+" mode="+sample.mode);
     if(!bitmap.hasGainmap())MiuiHook.log("HDR capture has no gainmap buffer; ratio alone cannot recover highlights");
    }catch(Throwable t){MiuiHook.log("Capture ratio unchanged: "+t);}
   }});
   MiuiHook.log("Capture ratio hook ready: "+name);
   try{HdrCaptureHooks.installAdapter(type);MiuiHook.log("High-precision HDR adapter ready: "+name);}
   catch(Throwable unavailable){MiuiHook.log("High-precision HDR adapter unavailable; native capture retained: "+unavailable);}
  }catch(ClassNotFoundException|NoSuchFieldException|NoSuchMethodException unavailable){MiuiHook.log("Capture ratio API absent: "+name);}
 }
}
