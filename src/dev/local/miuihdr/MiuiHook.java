package dev.local.miuihdr;
import android.graphics.Bitmap;
import android.util.Log;
import android.widget.ImageView;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.*;
public final class MiuiHook implements IXposedHookLoadPackage {
 private static final String STATE="dev.local.miuihdr.info";
 static void log(String s){Log.i("MiuiHdrHook",s);}
 private static ImageView imageView(Object controller)throws Exception{
  for(Field f:controller.getClass().getDeclaredFields())if(ImageView.class.isAssignableFrom(f.getType())){f.setAccessible(true);return (ImageView)f.get(controller);}return null;
 }
 public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p){
  if(!"com.miui.screenshot".equals(p.packageName))return;
  final ClassLoader cl=p.classLoader;
  NativeBridge.start();
  try{NativeProbeEntry.install();}catch(Throwable t){log("Root probe unavailable: "+t);}
  try{ShellMode.install(cl);}catch(Throwable t){log("HDR editor format hooks unavailable: "+t);}
  try{
   XposedBridge.hookMethod(cl.loadClass("com.miui.gallery.editor.photo.screen.core.ScreenDrawEntry").getDeclaredMethod("apply",Bitmap.class,boolean.class),new XC_MethodHook(){
    protected void beforeHookedMethod(MethodHookParam param){try{
     Bitmap b=(Bitmap)param.args[0];if(b==null||!b.hasGainmap())return;
     boolean copied=!((Boolean)param.args[1]);if(copied)b=b.copy(Bitmap.Config.ARGB_8888,false);
     param.setObjectExtra("hdr_source",b);param.setObjectExtra("hdr_owned",copied);
    }catch(Throwable t){log("Editor HDR snapshot skipped: "+t);}}
    protected void afterHookedMethod(MethodHookParam param){Bitmap source=(Bitmap)param.getObjectExtra("hdr_source");try{
     if(!param.hasThrowable()&&source!=null)ShellGainmap.repairDraw(param.thisObject,source,(Bitmap)param.getResult());
    }catch(Throwable t){log("Editor HDR restore skipped: "+t);}finally{if(Boolean.TRUE.equals(param.getObjectExtra("hdr_owned"))&&source!=null)source.recycle();}}
   });
  }catch(Throwable t){log("Editor draw hook unavailable: "+t);}
  for(String name:new String[]{"com.miui.gallery.editor.photo.screen.shell.ScreenShellEntry","com.miui.gallery.editor.photo.screen.shell.backscreen.BackScreenShellEntry"})try{
   XposedBridge.hookMethod(cl.loadClass(name).getDeclaredMethod("apply",Bitmap.class),new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam param){
    if(param.hasThrowable())return;
    try{Bitmap in=(Bitmap)param.args[0],out=(Bitmap)param.getResult();log("Shell input "+HdrSignal.inspect(in)+" original output "+HdrSignal.inspect(out));ShellGainmap.repair(param.thisObject,in,out);}
    catch(Throwable t){log("Shell HDR repair skipped: "+t);}
   }});
  }catch(Throwable t){log("Shell hook unavailable "+name+": "+t);}
  try{
   Class<?> gate=cl.loadClass("com.miui.screenshot.hyper.d");Method set=gate.getDeclaredMethod("a",int.class);
   XposedBridge.hookMethod(set,new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam param){
    if(!NativeBridge.gate&&((Integer)param.args[0])==0x1000000){param.args[0]=0;log("Kept display HDR enabled during native screenshot service");}
   }});
  }catch(Throwable t){log("HDR service hook unavailable: "+t);}
  try{
   Class<?> capture=cl.loadClass("e1.d");
   XposedBridge.hookMethod(capture.getDeclaredMethod("a",android.content.Context.class,int.class,android.graphics.Rect.class,String[].class),new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam param){
    HdrFrame.beforeCapture();java.util.LinkedHashSet<String> names=new java.util.LinkedHashSet<String>();if(param.args[3]!=null)java.util.Collections.addAll(names,(String[])param.args[3]);names.add("ScreenshotAnimation");names.add("RearScreenshotWindow");param.args[3]=names.toArray(new String[0]);log("Capture excludes preview layers; count="+names.size());
   }});
  }catch(Throwable t){log("Capture isolation unavailable: "+t);}
  try{
   Class<?> worker=cl.loadClass("com.miui.screenshot.hyper.TakeScreenshotExecutorImpl$processImageCapture$bitmap$1");
   XposedBridge.hookMethod(worker.getDeclaredMethod("invokeSuspend",Object.class),new XC_MethodHook(){protected void afterHookedMethod(MethodHookParam param){
    if(!param.hasThrowable()&&param.getResult() instanceof Bitmap){Bitmap b=(Bitmap)param.getResult();log("Native capture "+b.getWidth()+"x"+b.getHeight()+" "+HdrSignal.inspect(b));}
   }});
  }catch(Throwable t){log("Capture hook unavailable: "+t);}
  try{
   Class<?> controller=cl.loadClass("com.miui.screenshot.B");Method receive=null;
   for(Method m:controller.getDeclaredMethods())if(m.getName().equals("g")&&m.getParameterTypes().length==3&&m.getParameterTypes()[0].getName().equals("com.miui.screenshot.core.domain.ScreenshotData"))receive=m;
   if(receive==null)throw new NoSuchMethodException("Native screenshot controller");
   Method preview=controller.getDeclaredMethod("j2",Runnable.class);
   XposedBridge.hookMethod(receive,new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam param){try{
    Bitmap b=(Bitmap)param.args[0].getClass().getMethod("getBitmap").invoke(param.args[0]);HdrSignal.Info info=HdrSignal.inspect(b);
    XposedHelpers.setAdditionalInstanceField(param.thisObject,STATE,info);ImageView v=imageView(param.thisObject);if(v!=null)HdrFrame.clear(v);log("Native preview "+info);
   }catch(Throwable t){XposedHelpers.setAdditionalInstanceField(param.thisObject,STATE,null);log("Preview detection failed: "+t);}}});
   XposedBridge.hookMethod(preview,new XC_MethodHook(){protected void beforeHookedMethod(MethodHookParam param){try{
    HdrSignal.Info info=(HdrSignal.Info)XposedHelpers.getAdditionalInstanceField(param.thisObject,STATE);ImageView v=imageView(param.thisObject);
    if(v==null)return;HookConfig config=HookConfig.read(v.getContext());
    if(v!=null&&info!=null&&info.hdr&&(config.glow||config.badge))HdrFrame.attach(v,config,info.ratio);
    log("Preview settings glow="+config.glow+" hdrGlow="+config.hdrGlow+" badge="+config.badge);
   }catch(Throwable t){log("Preview feedback failed: "+t);}}});
  }catch(Throwable t){log("Preview hooks unavailable: "+t);}
  try{
   Class<?> writer=cl.loadClass("u1.e$a");
   for(Method m:writer.getDeclaredMethods())if(m.getName().equals("j")&&m.getParameterTypes().length==9){
    XposedBridge.hookMethod(m,new XC_MethodHook(){
     protected void beforeHookedMethod(MethodHookParam param){try{log("Native save "+HdrSignal.inspect((Bitmap)param.args[1]));}catch(Throwable t){log("Save inspection failed: "+t);}}
     protected void afterHookedMethod(MethodHookParam param){log("Native save result="+param.getResult()+" error="+param.hasThrowable());}
    });
   }
  }catch(Throwable t){log("Save observer unavailable: "+t);}
  log("Installed native MIUI HDR hooks v1.3.2 process="+p.processName);
 }
}
