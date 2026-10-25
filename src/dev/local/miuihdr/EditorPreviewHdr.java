package dev.local.miuihdr;
import dev.local.miuihdr.HookRuntime.Callback;
import dev.local.miuihdr.HookRuntime.Call;
import android.graphics.Bitmap;
import android.view.View;
import java.lang.reflect.*;

final class EditorPreviewHdr {
 private static final java.util.WeakHashMap<android.os.IBinder,HdrSignal.Info> TRANSITIONS=new java.util.WeakHashMap<>();
 static int earlyTransitions;
 static String lastPreview="not bound";
 static void rememberTransition(android.os.IBinder binder,HdrSignal.Info info){if(binder!=null)synchronized(TRANSITIONS){if(info==null)TRANSITIONS.remove(binder);else TRANSITIONS.put(binder,info);}}
 static Object field(Object instance,String name)throws Exception{
  for(Class<?> c=instance.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(instance);}catch(NoSuchFieldException ignored){}
  throw new NoSuchFieldException(name);
 }
 static void refresh(Object editor,boolean direct)throws Exception{
  if(!(editor instanceof View))return;
  Bitmap source=(Bitmap)field(editor,"mOriginBitmap"),shown=source;
  // The newer scribbles editor draws mOriginBitmap directly and never creates
  // mDisplayBitmap. The older editor draws a separate cropped software bitmap.
  // Do not create an SDR intermediate for the direct renderer.
  if(!direct){
   Bitmap display=(Bitmap)field(editor,"mDisplayBitmap");
   int top=0;try{top=((Number)field(editor,"mTopPixel")).intValue();}catch(NoSuchFieldException ignored){}
   shown=EditorGainmap.restore(source,display,top)?display:null;
  }
  HdrSignal.Info info=shown==null?null:HdrSignal.inspect(shown);
  PreviewHdr.bindEditor((View)editor,info);
  lastPreview=(direct?"direct":"composite")+" "+info;
  MiuiHook.log("Editor preview renderer="+(direct?"direct":"composite")+" "+info);
 }
 static void install(ClassLoader loader)throws Exception{
  int count=0;
  for(String name:new String[]{"com.miui.gallery.editor.photo.screen.base.ScreenBaseGestureView","com.miui.gallery.editor.photo.screen.home.ScreenEditorView","com.miui.gallery.editor.photo.scribbles.ImageEditorView"}){
   Class<?> type;try{type=loader.loadClass(name);}catch(ClassNotFoundException absent){continue;}
   final boolean direct=name.equals("com.miui.gallery.editor.photo.scribbles.ImageEditorView");
   for(String method:new String[]{"refreshDisplayCanvas","setBitmap","setPreviewBitmap"})try{
    Method target=type.getDeclaredMethod(method,method.equals("refreshDisplayCanvas")?new Class<?>[0]:new Class<?>[]{Bitmap.class});
    HookRuntime.add(target,new Callback(){protected void after(Call p){if(p.hasThrowable())return;try{refresh(p.thisObject,direct);}catch(Throwable t){MiuiHook.log("Editor HDR preview retained original on failure: "+t);}}});count++;
   }catch(NoSuchMethodException ignored){}
  }
  try{
   Class<?> activity=loader.loadClass("com.miui.gallery.editor.photo.screen.home.ScreenEditorActivity");
   HookRuntime.add(activity.getDeclaredMethod("setWindow"),new Callback(){protected void after(Call p){
    if(p.hasThrowable())return;
    android.app.Activity editor=(android.app.Activity)p.thisObject;
    android.content.Intent intent=editor.getIntent();android.os.Bundle extras=intent==null?null:intent.getExtras();
    android.os.IBinder binder=extras==null?null:extras.getBinder("key-transition-binder");
    HdrSignal.Info info;synchronized(TRANSITIONS){info=TRANSITIONS.get(binder);}
    if(info!=null&&info.hdr){PreviewHdr.prepareEditor(editor.getWindow(),info);EditorTransition.prepare(editor,binder);earlyTransitions++;MiuiHook.log("Editor transition starts in HDR before content attachment ratio="+info.ratio);}
   }});
  }catch(ReflectiveOperationException absent){MiuiHook.log("Editor early HDR transition unavailable: "+absent);}
  MiuiHook.log("Editor HDR preview hooks="+count+" shell geometry follows native clipping");
 }
}
