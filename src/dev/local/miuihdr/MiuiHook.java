package dev.local.miuihdr;

import android.graphics.Bitmap;
import android.widget.ImageView;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Modern libxposed entry; initialized only after the target classloader is ready. */
public final class MiuiHook extends XposedModule {
 private final WeakIdentityMap<HdrSignal.Info> previewState=new WeakIdentityMap<>();
 private String processName;
 static void log(String s){HdrLog.log(s);}
 private static ImageView imageView(Object controller)throws Exception{
  for(Field f:controller.getClass().getDeclaredFields())if(ImageView.class.isAssignableFrom(f.getType())){f.setAccessible(true);return (ImageView)f.get(controller);}return null;
 }
 @Override public void onModuleLoaded(ModuleLoadedParam param){processName=param.getProcessName();}
 @Override public boolean onHotReloading(HotReloadingParam param){
  // Native hooks/threads cannot be retired safely yet. Require a process restart.
  return false;
 }
 @Override public void onPackageReady(PackageReadyParam p){
  if(!"com.miui.screenshot".equals(p.getPackageName()))return;
  final ClassLoader cl=p.getClassLoader();
  NativeBridge.start();
  try{hook(android.view.View.class.getDeclaredMethod("setLayerType",int.class,android.graphics.Paint.class)).setId("preview.layer").intercept(chain -> {
   if(PreviewHdr.directDraw((android.view.View)chain.getThisObject()))return chain.proceed(new Object[]{android.view.View.LAYER_TYPE_NONE,chain.getArg(1)});
   return chain.proceed();
  });}catch(Throwable t){log("HDR preview direct drawing hook unavailable: "+t);}
  try{hook(Class.forName("android.view.WindowManagerImpl").getDeclaredMethod("updateViewLayout",android.view.View.class,android.view.ViewGroup.LayoutParams.class)).setId("preview.window").intercept(chain -> {
   Object layout=PreviewHdr.keepHdr((android.view.View)chain.getArg(0),(android.view.ViewGroup.LayoutParams)chain.getArg(1));
   return chain.proceed(new Object[]{chain.getArg(0),layout});
  });}catch(Throwable t){log("HDR preview window update hook unavailable: "+t);}
  try{PrecisionHooks.install(this);}catch(Throwable t){log("Pixel HDR encoder hook unavailable: "+t);}
  try{NativeProbeEntry.install(this);}catch(Throwable t){log("Root probe unavailable: "+t);}
  try{ShellMode.install(this,cl);}catch(Throwable t){log("HDR editor format hooks unavailable: "+t);}
  try{
   hook(cl.loadClass("com.miui.gallery.editor.photo.screen.core.ScreenDrawEntry").getDeclaredMethod("apply",Bitmap.class,boolean.class)).setId("editor.draw").intercept(chain -> {
    Bitmap source=null;boolean owned=false;
    try{
     Bitmap input=(Bitmap)chain.getArg(0);
     if(input!=null&&input.hasGainmap()){
      owned=!((Boolean)chain.getArg(1));source=owned?input.copy(Bitmap.Config.ARGB_8888,false):input;
     }
    }catch(Throwable t){log("Editor HDR snapshot skipped: "+t);}
    try{
     Object result=chain.proceed();
     try{if(source!=null)ShellGainmap.repairDraw(chain.getThisObject(),source,(Bitmap)result);}
     catch(Throwable t){log("Editor HDR restore skipped: "+t);}
     return result;
    }finally{if(owned&&source!=null)source.recycle();}
   });
  }catch(Throwable t){log("Editor draw hook unavailable: "+t);}
  for(String name:new String[]{"com.miui.gallery.editor.photo.screen.shell.ScreenShellEntry","com.miui.gallery.editor.photo.screen.shell.backscreen.BackScreenShellEntry"})try{
   hook(cl.loadClass(name).getDeclaredMethod("apply",Bitmap.class)).setId("editor.shell").intercept(chain -> {
    Object result=chain.proceed();
    try{Bitmap in=(Bitmap)chain.getArg(0),out=(Bitmap)result;log("Shell input "+HdrSignal.inspect(in)+" original output "+HdrSignal.inspect(out));ShellGainmap.repair(chain.getThisObject(),in,out);}
    catch(Throwable t){log("Shell HDR repair skipped: "+t);}
    return result;
   });
  }catch(Throwable t){log("Shell hook unavailable "+name+": "+t);}
  try{
   Class<?> gate=cl.loadClass("com.miui.screenshot.hyper.d");Method set=gate.getDeclaredMethod("a",int.class);
   hook(set).setId("capture.hdrGate").intercept(chain -> {
    if(!NativeBridge.gate&&((Integer)chain.getArg(0))==0x1000000){log("Kept display HDR enabled during native screenshot service");return chain.proceed(new Object[]{0});}
    return chain.proceed();
   });
  }catch(Throwable t){log("HDR service hook unavailable: "+t);}
  try{
   Class<?> capture=cl.loadClass("e1.d");
   hook(capture.getDeclaredMethod("a",android.content.Context.class,int.class,android.graphics.Rect.class,String[].class)).setId("capture.excludePreview").intercept(chain -> {
    HdrFrame.beforeCapture();java.util.LinkedHashSet<String> names=new java.util.LinkedHashSet<>();
    if(chain.getArg(3)!=null)java.util.Collections.addAll(names,(String[])chain.getArg(3));
    names.add("ScreenshotAnimation");names.add("RearScreenshotWindow");log("Capture excludes preview layers; count="+names.size());
    Object[] args=chain.getArgs().toArray();args[3]=names.toArray(new String[0]);return chain.proceed(args);
   });
  }catch(Throwable t){log("Capture isolation unavailable: "+t);}
  try{
   Class<?> worker=cl.loadClass("com.miui.screenshot.hyper.TakeScreenshotExecutorImpl$processImageCapture$bitmap$1");
   hook(worker.getDeclaredMethod("invokeSuspend",Object.class)).setId("capture.precision").intercept(chain -> {
    Object result=chain.proceed();
    if(result instanceof Bitmap){Bitmap b=(Bitmap)result;
     try{b=PrecisionHooks.capture(b);}catch(Throwable t){log("Pixel capture skipped; original retained: "+t);}
     log("Native capture "+b.getWidth()+"x"+b.getHeight()+" "+HdrSignal.inspect(b));return b;
    }
    return result;
   });
  }catch(Throwable t){log("Capture hook unavailable: "+t);}
  try{
   Class<?> controller=cl.loadClass("com.miui.screenshot.B");Method receive=null;
   for(Method m:controller.getDeclaredMethods())if(m.getName().equals("g")&&m.getParameterTypes().length==3&&m.getParameterTypes()[0].getName().equals("com.miui.screenshot.core.domain.ScreenshotData"))receive=m;
   if(receive==null)throw new NoSuchMethodException("Native screenshot controller");
   // i2 binds the original gainmap bitmap before the full-screen entry frame;
   // j2 only begins the later transition into the corner thumbnail.
   Method preview=controller.getDeclaredMethod("i2",Runnable.class);
   hook(receive).setId("preview.detect").intercept(chain -> {
    Object controllerInstance=chain.getThisObject();
    try{
     Bitmap b=(Bitmap)chain.getArg(0).getClass().getMethod("getBitmap").invoke(chain.getArg(0));HdrSignal.Info info=HdrSignal.inspect(b);
     previewState.put(controllerInstance,info);ImageView v=imageView(controllerInstance);if(v!=null){HdrFrame.clear(v);PreviewHdr.clear(v);}log("Native preview "+info);
    }catch(Throwable t){previewState.put(controllerInstance,null);log("Preview detection failed: "+t);}
    return chain.proceed();
   });
   hook(preview).setId("preview.feedback").intercept(chain -> {
    try{
     HdrSignal.Info info=previewState.get(chain.getThisObject());ImageView v=imageView(chain.getThisObject());
     if(v!=null){PreviewHdr.bind(v,info);HookConfig config=HookConfig.read(v.getContext());
      if(info!=null&&info.hdr&&(config.glow||config.badge))HdrFrame.attach(v,config,info.ratio);
      log("Preview settings glow="+config.glow+" hdrGlow="+config.hdrGlow+" badge="+config.badge);
     }
    }catch(Throwable t){log("Preview feedback failed: "+t);}
    return chain.proceed();
   });
  }catch(Throwable t){log("Preview hooks unavailable: "+t);}
  try{
   Class<?> writer=cl.loadClass("u1.e$a");
   for(Method m:writer.getDeclaredMethods())if(m.getName().equals("j")&&m.getParameterTypes().length==9){
    hook(m).setId("save.inspect").intercept(chain -> {
     try{log("Native save "+HdrSignal.inspect((Bitmap)chain.getArg(1)));}catch(Throwable t){log("Save inspection failed: "+t);}
     try{Object result=chain.proceed();log("Native save result="+result+" error=false");return result;}
     catch(Throwable t){log("Native save result=null error=true");throw t;}
    });
   }
  }catch(Throwable t){log("Save observer unavailable: "+t);}
  log("Installed native MIUI HDR hooks using libxposed API "+getApiVersion()+" process="+processName);
 }
}
