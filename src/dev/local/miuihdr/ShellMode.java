package dev.local.miuihdr;

import android.graphics.Bitmap;
import android.view.View;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Per-editor shell format: off -> JPEG -> transparent PNG -> off. */
final class ShellMode {
 private static final WeakIdentityMap<Integer> MODES=new WeakIdentityMap<>();
 private static final int OFF=0,JPEG=1,PNG=2;
 private static int mode(Object imageManager){Integer value=MODES.get(imageManager);return value!=null?value:OFF;}
 private static void setMode(Object imageManager,int mode){MODES.put(imageManager,mode==OFF?null:mode);}
 private static Field field(Object instance,String name)throws ReflectiveOperationException{
  for(Class<?> type=instance.getClass();type!=null;type=type.getSuperclass())try{
   Field field=type.getDeclaredField(name);field.setAccessible(true);return field;
  }catch(NoSuchFieldException ignored){}
  throw new NoSuchFieldException(instance.getClass().getName()+"."+name);
 }
 private static Object get(Object instance,String name)throws ReflectiveOperationException{return field(instance,name).get(instance);}
 private static void set(Object instance,String name,Object value)throws ReflectiveOperationException{field(instance,name).set(instance,value);}
 private static Object call(Object instance,String name)throws ReflectiveOperationException{return invoke(instance,name,new Class<?>[0]);}
 private static void setNeedSaveAsPng(Object instance,boolean value)throws ReflectiveOperationException{invoke(instance,"setNeedSaveAsPng",new Class<?>[]{boolean.class},value);}
 private static Object invoke(Object instance,String name,Class<?>[] types,Object... args)throws ReflectiveOperationException{
  for(Class<?> type=instance.getClass();type!=null;type=type.getSuperclass())try{
   Method method=type.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(instance,args);
  }catch(NoSuchMethodException ignored){}
  throw new NoSuchMethodException(instance.getClass().getName()+"."+name);
 }
 private static boolean hasShell(Object manager)throws ReflectiveOperationException{
  Object operation=call(manager,"getShellOperation");return operation!=null&&Boolean.TRUE.equals(call(operation,"isWithShell"));
 }
 private static Object imageManager(Object shellManager)throws ReflectiveOperationException{return get(shellManager,"mScreenImageManager");}
 private static void updateButton(Object imageManager)throws ReflectiveOperationException{
  Object activity=get(imageManager,"mActivity");
  TextView on=(TextView)get(activity,"mShellTextViewOn"),off=(TextView)get(activity,"mShellTextViewOff");
  View button=(View)get(activity,"mShellLayoutView");if(on==null||off==null||button==null)return;
  int state=mode(imageManager);Bitmap origin=(Bitmap)get(imageManager,"mOriginBitmap");
  boolean hdr=origin!=null&&!origin.isRecycled()&&origin.hasGainmap();
  String label=state==OFF?"带壳":state==JPEG?"带壳 · JPG":hdr?"带壳 · PNG HDR":"带壳 · PNG";
  String next=state==OFF?"点击使用 JPG 带壳":state==JPEG?"点击切换透明 PNG":"点击关闭加壳";
  // Xiaomi keeps two labels in the same slot; both must size to the current text.
  for(TextView text:new TextView[]{on,off}){text.setText(label);text.setContentDescription(label+"，"+next+"，按钮");}
  button.setStateDescription(label);button.setTooltipText(next);
 }
 static void install(XposedInterface api,ClassLoader cl)throws Exception{
  Class<?> shell=cl.loadClass("com.miui.gallery.editor.photo.scribbles.ScreenShellManager");
  Class<?> image=cl.loadClass("com.miui.gallery.editor.photo.scribbles.ScreenImageManager");
  Class<?> activity=cl.loadClass("com.miui.gallery.editor.photo.screen.home.ScreenEditorActivity");
  api.hook(shell.getDeclaredMethod("executeShellOperation")).setId("shell.toggle").intercept(chain -> {
   Object im=null;Integer previous=null;boolean formatOnly=false;
   try{
    im=imageManager(chain.getThisObject());
    if(im!=null){
     boolean active=hasShell(chain.getThisObject());
     if(active&&mode(im)!=PNG){
      // A format-only change must not call Xiaomi's binary shell toggle.
      setMode(im,PNG);setNeedSaveAsPng(im,true);formatOnly=true;
      updateButton(im);HdrLog.log("Shell format PNG; retained shell and transparency");
     }else{previous=mode(im);setMode(im,active?OFF:JPEG);}
    }
   }catch(Throwable t){HdrLog.log("Shell format switch unavailable: "+t);}
   if(formatOnly)return null;
   Object result;
   try{result=chain.proceed();}
   catch(Throwable original){
    try{if(im!=null){if(previous!=null)setMode(im,previous);updateButton(im);}}
    catch(Throwable t){HdrLog.log("Shell format state unavailable: "+t);}
    throw original;
   }
   try{
    im=imageManager(chain.getThisObject());
    if(im!=null){
     boolean active=hasShell(chain.getThisObject());setMode(im,active?JPEG:OFF);setNeedSaveAsPng(im,false);
     HdrLog.log("Shell format "+(active?"JPG":"OFF"));updateButton(im);
    }
   }catch(Throwable t){HdrLog.log("Shell format state unavailable: "+t);}
   return result;
  });
  api.hook(activity.getDeclaredMethod("updateShellViewState",boolean.class)).setId("shell.button").intercept(chain -> {
   Object result=chain.proceed();
   try{
    Object model=get(chain.getThisObject(),"mViewModel"),manager=call(model,"getShellManager"),im=imageManager(manager);
    if(im!=null){
     if(!Boolean.TRUE.equals(chain.getArg(0)))setMode(im,OFF);else if(mode(im)==OFF)setMode(im,JPEG);
     updateButton(im);
    }
   }catch(Throwable t){HdrLog.log("Shell button update unavailable: "+t);}
   return result;
  });
  api.hook(image.getDeclaredMethod("isSavedAsPng")).setId("shell.format").intercept(chain -> {
   Object result=chain.proceed();
   try{
    int state=mode(chain.getThisObject());if(state==PNG)return true;
    Bitmap b=(Bitmap)call(chain.getThisObject(),"getRenderBitmap");if(state==JPEG||(b!=null&&b.hasGainmap()))return false;
   }catch(Throwable t){HdrLog.log("HDR export format check: "+t);}
   return result;
  });
  api.hook(image.getDeclaredMethod("createExportData")).setId("shell.export").intercept(chain -> {
   Object data=chain.proceed();
   try{
    if(data==null)return null;Bitmap b=(Bitmap)get(data,"image");if(b==null)return data;
    int state=mode(chain.getThisObject());
    if(state==PNG){
     set(data,"mBitmapFormat",Bitmap.CompressFormat.PNG);
     HdrLog.log("Editor export PNG HDR="+b.hasGainmap()+" "+b.getWidth()+"x"+b.getHeight()+" alpha="+b.hasAlpha());
    }else if(state==JPEG||b.hasGainmap()){
     Bitmap jpeg=ShellGainmap.flattenForJpeg(b);set(data,"image",jpeg);set(data,"mBitmapFormat",Bitmap.CompressFormat.JPEG);
     HdrLog.log("Editor export Ultra HDR JPEG "+jpeg.getWidth()+"x"+jpeg.getHeight()+" alpha="+jpeg.hasAlpha());
    }
   }catch(Throwable t){HdrLog.log("HDR export preparation failed: "+t);}
   return data;
  });
 }
}
