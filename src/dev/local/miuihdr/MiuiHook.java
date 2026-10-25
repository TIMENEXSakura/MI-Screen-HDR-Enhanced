package dev.local.miuihdr;
import dev.local.miuihdr.HookRuntime.Callback;
import dev.local.miuihdr.HookRuntime.Call;
import android.graphics.Bitmap;
import android.util.Log;
import android.widget.ImageView;
import java.lang.reflect.*;
public final class MiuiHook {
 private static final String STATE="dev.local.miuihdr.info";
 static void log(String s){HookRuntime.log(s);}
 private static ImageView imageView(Object controller)throws Exception{
  for(Field f:controller.getClass().getDeclaredFields())if(ImageView.class.isAssignableFrom(f.getType())){f.setAccessible(true);return (ImageView)f.get(controller);}return null;
 }
 static void install(ClassLoader cl,String process){
  NativeBridge.start();
  try{HookRuntime.add(android.view.View.class.getDeclaredMethod("setLayerType",int.class,android.graphics.Paint.class),new Callback(){
   protected void before(Call param){if(PreviewHdr.directDraw((android.view.View)param.thisObject))param.args[0]=android.view.View.LAYER_TYPE_NONE;}
  });}catch(Throwable t){log("HDR preview direct drawing hook unavailable: "+t);}
  try{HookRuntime.add(Class.forName("android.view.WindowManagerImpl").getDeclaredMethod("updateViewLayout",android.view.View.class,android.view.ViewGroup.LayoutParams.class),new Callback(){
   protected void before(Call param){param.args[1]=PreviewHdr.keepHdr((android.view.View)param.args[0],(android.view.ViewGroup.LayoutParams)param.args[1]);}
  });}catch(Throwable t){log("HDR preview window update hook unavailable: "+t);}
  try{EditorTransition.install(cl);}catch(Throwable t){log("Editor fade hook unavailable: "+t);}
  try{EditorPreviewHdr.install(cl);}catch(Throwable t){log("Editor HDR preview hook unavailable: "+t);}
  CropHooks.install(cl);
  try{CaptureRatioHooks.install();}catch(Throwable t){log("Capture ratio unavailable: "+t);}
  try{PrecisionHooks.install();}catch(Throwable t){log("Pixel HDR encoder hook unavailable: "+t);}
  try{NativeProbeEntry.install();}catch(Throwable t){log("Root probe unavailable: "+t);}
  try{ShellMode.install(cl);}catch(Throwable t){log("HDR editor format hooks unavailable: "+t);}
  try{
   HookRuntime.add(cl.loadClass("com.miui.gallery.editor.photo.screen.core.ScreenDrawEntry").getDeclaredMethod("apply",Bitmap.class,boolean.class),new Callback(){
    protected void before(Call param){try{
     Bitmap b=(Bitmap)param.args[0];if(b==null||!b.hasGainmap())return;
     boolean copied=!((Boolean)param.args[1]);if(copied)b=b.copy(Bitmap.Config.ARGB_8888,false);
     param.setObjectExtra("hdr_source",b);param.setObjectExtra("hdr_owned",copied);
    }catch(Throwable t){log("Editor HDR snapshot skipped: "+t);}}
    protected void after(Call param){Bitmap source=(Bitmap)param.getObjectExtra("hdr_source");try{
     if(!param.hasThrowable()&&source!=null)ShellGainmap.repairDraw(param.thisObject,source,(Bitmap)param.getResult());
    }catch(Throwable t){log("Editor HDR restore skipped: "+t);}finally{if(Boolean.TRUE.equals(param.getObjectExtra("hdr_owned"))&&source!=null)source.recycle();}}
   });
  }catch(Throwable t){log("Editor draw hook unavailable: "+t);}
  for(String name:new String[]{"com.miui.gallery.editor.photo.screen.shell.ScreenShellEntry","com.miui.gallery.editor.photo.screen.shell.backscreen.BackScreenShellEntry"})try{
   HookRuntime.add(cl.loadClass(name).getDeclaredMethod("apply",Bitmap.class),new Callback(){protected void after(Call param){
    if(param.hasThrowable())return;
    try{Bitmap in=(Bitmap)param.args[0],out=(Bitmap)param.getResult();log("Shell input "+HdrSignal.inspect(in)+" original output "+HdrSignal.inspect(out));ShellGainmap.repair(param.thisObject,in,out);}
    catch(Throwable t){log("Shell HDR repair skipped: "+t);}
   }});
  }catch(Throwable t){log("Shell hook unavailable "+name+": "+t);}
  try{
   Class<?> gate=cl.loadClass("com.miui.screenshot.hyper.d");Method set=gate.getDeclaredMethod("a",int.class);
   HookRuntime.add(set,new Callback(){protected void before(Call param){
    if(!NativeBridge.gate&&((Integer)param.args[0])==0x1000000){param.args[0]=0;log("Kept display HDR enabled during native screenshot service");}
   }});
  }catch(Throwable t){log("HDR service hook unavailable: "+t);}
  try{
   Method capture;
   try{capture=cl.loadClass("com.miui.screenshot.core.util.DisplayCapture").getDeclaredMethod("captureDisplay",android.content.Context.class,int.class,android.graphics.Rect.class,String[].class);}
   catch(ReflectiveOperationException absent){capture=cl.loadClass("e1.d").getDeclaredMethod("a",android.content.Context.class,int.class,android.graphics.Rect.class,String[].class);}
   HookRuntime.add(capture,new Callback(){protected void before(Call param){
    CaptureRatioHooks.begin((android.content.Context)param.args[0],(Integer)param.args[1]);
    HdrFrame.beforeCapture();java.util.LinkedHashSet<String> names=new java.util.LinkedHashSet<String>();if(param.args[3]!=null)java.util.Collections.addAll(names,(String[])param.args[3]);names.add("ScreenshotAnimation");names.add("RearScreenshotWindow");param.args[3]=names.toArray(new String[0]);log("Capture excludes preview layers; count="+names.size());
   }protected void after(Call param){CaptureRatioHooks.end();}});
  }catch(Throwable t){log("Capture isolation unavailable: "+t);}
  try{
   Class<?> worker=cl.loadClass("com.miui.screenshot.hyper.TakeScreenshotExecutorImpl$processImageCapture$bitmap$1");
   HookRuntime.add(worker.getDeclaredMethod("invokeSuspend",Object.class),new Callback(){
    protected void before(Call param){CaptureAudit.begin();}
    protected void after(Call param){
     Bitmap b=null;HdrSignal.Info info=null;
     try{
      if(!param.hasThrowable()&&param.getResult() instanceof Bitmap){b=(Bitmap)param.getResult();CaptureAudit.converted(b);
       // Preserve the native SDR/HDR separation, including gains in midtones.
       // Reconstructing and clipping to SDR white moves those gains into the
       // base and makes only the brightest regions respond to HDR headroom.
       info=HdrSignal.inspect(b);log("Native capture "+b.getWidth()+"x"+b.getHeight()+" "+info);
      }
     }finally{
      android.content.Context context=null;try{context=HookObjects.currentApplication();}catch(Throwable ignored){}
      CaptureAudit.end(context,b,info);
     }
   }});
  }catch(Throwable t){log("Capture hook unavailable: "+t);}
  try{
   Class<?> controller=cl.loadClass("com.miui.screenshot.B");Method receive=null;
   for(Method m:controller.getDeclaredMethods())if(m.getName().equals("g")&&m.getParameterTypes().length==3&&m.getParameterTypes()[0].getName().equals("com.miui.screenshot.core.domain.ScreenshotData"))receive=m;
   if(receive==null)throw new NoSuchMethodException("Native screenshot controller");
   // i2 binds the original gainmap bitmap before the full-screen entry frame;
   // j2 only begins the later transition into the corner thumbnail.
   Method preview=controller.getDeclaredMethod("i2",Runnable.class);
   try{HookRuntime.add(controller.getDeclaredMethod("i1"),new Callback(){protected void after(Call param){
    if(!param.hasThrowable()&&param.getResult() instanceof android.os.IBinder){
     android.os.IBinder binder=(android.os.IBinder)param.getResult();
     EditorPreviewHdr.rememberTransition(binder,(HdrSignal.Info)HookObjects.getAdditionalInstanceField(param.thisObject,STATE));
     try{EditorTransition.remember(binder,imageView(param.thisObject));}catch(Throwable t){log("Editor bloom handoff unavailable: "+t);}
    }
   }});}catch(ReflectiveOperationException absent){log("Thumbnail HDR handoff unavailable: "+absent);}
   HookRuntime.add(receive,new Callback(){protected void before(Call param){try{
    Bitmap b=(Bitmap)param.args[0].getClass().getMethod("getBitmap").invoke(param.args[0]);HdrSignal.Info info=HdrSignal.inspect(b);
    HookObjects.setAdditionalInstanceField(param.thisObject,STATE,info);ImageView v=imageView(param.thisObject);if(v!=null){HdrFrame.clear(v);PreviewHdr.clear(v);}log("Native preview "+info);
   }catch(Throwable t){HookObjects.setAdditionalInstanceField(param.thisObject,STATE,null);log("Preview detection failed: "+t);}}});
   HookRuntime.add(preview,new Callback(){protected void before(Call param){try{
    HdrSignal.Info info=(HdrSignal.Info)HookObjects.getAdditionalInstanceField(param.thisObject,STATE);ImageView v=imageView(param.thisObject);
    if(v==null)return;PreviewHdr.bind(v,info);HookConfig config=HookConfig.read(v.getContext());
    if(v!=null&&info!=null&&info.hdr&&(config.glow||config.badge))HdrFrame.attach(v,config,info.ratio);
    log("Preview settings glow="+config.glow+" hdrGlow="+config.hdrGlow+" badge="+config.badge);
   }catch(Throwable t){log("Preview feedback failed: "+t);}}});
   HookRuntime.add(controller.getDeclaredMethod("j2",Runnable.class),new Callback(){
    protected void before(Call param){
     java.util.HashSet<Object> previous=new java.util.HashSet<>();
     for(Field f:param.thisObject.getClass().getDeclaredFields())if(android.animation.AnimatorSet.class.isAssignableFrom(f.getType()))try{f.setAccessible(true);previous.add(f.get(param.thisObject));}catch(Throwable ignored){}
     param.setObjectExtra("hdr_previous_animators",previous);
    }
    protected void after(Call param){try{
     if(param.hasThrowable())return;ImageView v=imageView(param.thisObject);if(v==null)return;
     HdrFrame frame=HdrFrame.current(v);if(frame==null)return;
     java.util.Set<?> previous=(java.util.Set<?>)param.getObjectExtra("hdr_previous_animators");
     android.animation.AnimatorSet selected=null;
     for(Field f:param.thisObject.getClass().getDeclaredFields())if(android.animation.AnimatorSet.class.isAssignableFrom(f.getType())){
      f.setAccessible(true);Object candidate=f.get(param.thisObject);
      if(candidate!=null&&!previous.contains(candidate)){if(selected!=null)return;selected=(android.animation.AnimatorSet)candidate;}
     }
     if(selected==null)return;
     if(!selected.isStarted()){HdrFrame.thumbnailReady(v,frame);return;}
     selected.addListener(new android.animation.AnimatorListenerAdapter(){
      boolean canceled;
      public void onAnimationCancel(android.animation.Animator a){canceled=true;}
      public void onAnimationEnd(android.animation.Animator a){a.removeListener(this);if(!canceled)HdrFrame.thumbnailReady(v,frame);}
     });
    }catch(Throwable t){log("Thumbnail badge transition unavailable: "+t);}}
   });
  }catch(Throwable t){log("Preview hooks unavailable: "+t);}
  try{
   Class<?> writer=cl.loadClass("u1.e$a");
   for(Method m:writer.getDeclaredMethods())if(m.getName().equals("j")&&m.getParameterTypes().length==9){
    HookRuntime.add(m,new Callback(){
     protected void before(Call param){try{log("Native save "+HdrSignal.inspect((Bitmap)param.args[1]));}catch(Throwable t){log("Save inspection failed: "+t);}}
     protected void after(Call param){log("Native save result="+param.getResult()+" error="+param.hasThrowable());}
    });
   }
  }catch(Throwable t){log("Save observer unavailable: "+t);}
  log("Installed native MIUI HDR hooks v1.5.2-hdrfix.2 process="+process);
 }
}
