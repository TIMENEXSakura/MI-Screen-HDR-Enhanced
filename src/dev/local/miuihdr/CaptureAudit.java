package dev.local.miuihdr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Gainmap;
import android.hardware.HardwareBuffer;
import android.os.SystemClock;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;

/** Last capture's numeric diagnostics only; never stores image pixels or app content. */
final class CaptureAudit {
 private static final ThreadLocal<StringBuilder> ACTIVE=new ThreadLocal<>();
 static void begin(){ACTIVE.set(new StringBuilder("capture_time_ms="+System.currentTimeMillis()+"\nuptime_ms="+SystemClock.uptimeMillis()+"\n"));}
 private static String metadata(Bitmap bitmap){
  if(bitmap==null||bitmap.isRecycled())return "bitmap=unavailable";
  String value="size="+bitmap.getWidth()+"x"+bitmap.getHeight()+" config="+bitmap.getConfig()+" gainmap="+bitmap.hasGainmap();
  if(bitmap.hasGainmap()){
   Gainmap map=bitmap.getGainmap();
   value+=" ratioMin="+Arrays.toString(map.getRatioMin())+" ratioMax="+Arrays.toString(map.getRatioMax())+" fullHdr="+map.getDisplayRatioForFullHdr();
  }
  return value;
 }
 static void nativeBuffer(Object result,Bitmap bitmap,boolean hdr,boolean secure,float ratio){
  StringBuilder log=ACTIVE.get();if(log==null)return;
  try{
   log.append("native hdr=").append(hdr).append(" secure=").append(secure).append(" ratio=").append(ratio);
   if(!secure){
    HardwareBuffer buffer=(HardwareBuffer)result.getClass().getDeclaredMethod("getHardwareBuffer").invoke(result);
    if(buffer!=null)log.append(" bufferFormat=").append(buffer.getFormat());
    try{Field field=result.getClass().getDeclaredField("mGainmap");field.setAccessible(true);
     HardwareBuffer gain=(HardwareBuffer)field.get(result);
     log.append(" gainBuffer=").append(gain!=null);
     if(gain!=null)log.append(" gainFormat=").append(gain.getFormat());
    }catch(ReflectiveOperationException unavailable){log.append(" gainBuffer=unknown");}
    log.append(' ').append(metadata(bitmap));
   }
   log.append('\n');
  }catch(Throwable error){log.append("\nnative_diagnostic_unavailable=").append(error.getClass().getSimpleName()).append('\n');}
 }
 static void converted(Bitmap bitmap){
  StringBuilder log=ACTIVE.get();if(log!=null)try{log.append("before_precision ").append(metadata(bitmap)).append('\n');}catch(Throwable ignored){}
 }
 static void precision(int highlights,Bitmap source,Bitmap softwareMap){
  StringBuilder log=ACTIVE.get();if(log==null)return;
  try{
   log.append("precision_highlight_pixels=").append(highlights);
   // Only an empty candidate needs this extra pass. Reuse the compositor's
   // software map, avoiding another hardware copy in the normal capture path.
   if(highlights==0)log.append(" source_first_signal=").append(NativeBridge.firstSignal(softwareMap)).append(" original_retained=true");
   log.append('\n');
  }catch(Throwable ignored){log.append(" diagnostic_unavailable\n");}
 }
 static void end(Context context,Bitmap output,HdrSignal.Info info){
  StringBuilder log=ACTIVE.get();ACTIVE.remove();if(log==null)return;
  try{
   log.append("output ").append(metadata(output)).append(" signal=").append(info).append(" end_uptime_ms=").append(SystemClock.uptimeMillis()).append('\n');
   MiuiHook.log("Capture audit "+log.toString().replace('\n',';'));
   if(context!=null)write(context,log.toString());
  }catch(Throwable error){MiuiHook.log("Capture audit unavailable: "+error.getClass().getSimpleName());}
 }
 private static synchronized void write(Context context,String value)throws Exception{
  File file=new File(context.getCacheDir(),"mi-screen-hdr-capture.txt");
  Files.writeString(file.toPath(),value);
 }
}
