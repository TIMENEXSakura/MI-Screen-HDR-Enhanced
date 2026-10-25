package dev.local.miuihdr;
import dev.local.miuihdr.HookRuntime.Callback;
import dev.local.miuihdr.HookRuntime.Call;

import android.graphics.Bitmap;
import android.view.View;
import android.widget.TextView;

/** Per-editor shell format: off -> JPEG -> transparent PNG -> off. */
final class ShellMode {
 private static final String KEY="dev.local.miuihdr.shellFormat";
 private static final int OFF=0,JPEG=1,PNG=2;
 private static int mode(Object imageManager){
  Object value=HookObjects.getAdditionalInstanceField(imageManager,KEY);
  return value instanceof Integer?(Integer)value:OFF;
 }
 private static void setMode(Object imageManager,int mode){HookObjects.setAdditionalInstanceField(imageManager,KEY,mode);}
 private static boolean hasShell(Object manager){
  Object operation=HookObjects.callMethod(manager,"getShellOperation");
  return operation!=null&&Boolean.TRUE.equals(HookObjects.callMethod(operation,"isWithShell"));
 }
 private static Object imageManager(Object shellManager){return HookObjects.getObjectField(shellManager,"mScreenImageManager");}
 private static void updateButton(Object imageManager){
  Object activity=HookObjects.getObjectField(imageManager,"mActivity");
  TextView on=(TextView)HookObjects.getObjectField(activity,"mShellTextViewOn");
  TextView off=(TextView)HookObjects.getObjectField(activity,"mShellTextViewOff");
  View button=(View)HookObjects.getObjectField(activity,"mShellLayoutView");
  if(on==null||off==null||button==null)return;
  int state=mode(imageManager);
  Bitmap origin=(Bitmap)HookObjects.getObjectField(imageManager,"mOriginBitmap");
  boolean hdr=origin!=null&&!origin.isRecycled()&&origin.hasGainmap();
  String label=state==OFF?"带壳":state==JPEG?"带壳 · JPG":hdr?"带壳 · PNG HDR":"带壳 · PNG";
  String next=state==OFF?"点击使用 JPG 带壳":state==JPEG?"点击切换透明 PNG":"点击关闭加壳";
  // Xiaomi keeps two labels in the same slot; both must size to the current text.
  for(TextView text:new TextView[]{on,off}){text.setText(label);text.setContentDescription(label+"，"+next+"，按钮");}
  button.setStateDescription(label);
  button.setTooltipText(next);
 }
 static void install(ClassLoader cl)throws Exception{
  Class<?> shell=cl.loadClass("com.miui.gallery.editor.photo.scribbles.ScreenShellManager");
  Class<?> image=cl.loadClass("com.miui.gallery.editor.photo.scribbles.ScreenImageManager");
  Class<?> activity=cl.loadClass("com.miui.gallery.editor.photo.screen.home.ScreenEditorActivity");
  HookRuntime.add(shell.getDeclaredMethod("executeShellOperation"),new Callback(){
   protected void before(Call param){try{
    Object im=imageManager(param.thisObject);if(im==null)return;
    boolean active=hasShell(param.thisObject);
    if(active&&mode(im)!=PNG){
     // A format-only change must not call Xiaomi's binary shell toggle.
     setMode(im,PNG);HookObjects.callMethod(im,"setNeedSaveAsPng",true);
     param.setObjectExtra("format_only",true);param.setResult(null);
     updateButton(im);MiuiHook.log("Shell format PNG; retained shell and transparency");
    }else{
     param.setObjectExtra("previous_mode",mode(im));
     setMode(im,active?OFF:JPEG);
    }
   }catch(Throwable t){MiuiHook.log("Shell format switch unavailable: "+t);}}
   protected void after(Call param){try{
    if(Boolean.TRUE.equals(param.getObjectExtra("format_only")))return;
    Object im=imageManager(param.thisObject);if(im==null)return;
    if(param.hasThrowable()){
     Object previous=param.getObjectExtra("previous_mode");if(previous instanceof Integer)setMode(im,(Integer)previous);
    }else{
     boolean active=hasShell(param.thisObject);
     setMode(im,active?JPEG:OFF);
     HookObjects.callMethod(im,"setNeedSaveAsPng",false);
     MiuiHook.log("Shell format "+(active?"JPG":"OFF"));
    }
    updateButton(im);
   }catch(Throwable t){MiuiHook.log("Shell format state unavailable: "+t);}}
  });
  HookRuntime.add(activity.getDeclaredMethod("updateShellViewState",boolean.class),new Callback(){
   protected void after(Call param){try{
    Object model=HookObjects.getObjectField(param.thisObject,"mViewModel");
    Object manager=HookObjects.callMethod(model,"getShellManager");Object im=imageManager(manager);if(im==null)return;
    if(!Boolean.TRUE.equals(param.args[0]))setMode(im,OFF);
    else if(mode(im)==OFF)setMode(im,JPEG);
    updateButton(im);
   }catch(Throwable t){MiuiHook.log("Shell button update unavailable: "+t);}}
  });
  HookRuntime.add(image.getDeclaredMethod("isSavedAsPng"),new Callback(){
   protected void after(Call param){try{
    int state=mode(param.thisObject);
    if(state==PNG){param.setResult(true);return;}
    Bitmap b=(Bitmap)HookObjects.callMethod(param.thisObject,"getRenderBitmap");
    if(state==JPEG||(b!=null&&b.hasGainmap()))param.setResult(false);
   }catch(Throwable t){MiuiHook.log("HDR export format check: "+t);}}
  });
  HookRuntime.add(image.getDeclaredMethod("createExportData"),new Callback(){
   protected void after(Call param){try{
    Object data=param.getResult();if(data==null)return;
    Bitmap b=(Bitmap)HookObjects.getObjectField(data,"image");if(b==null)return;
    int state=mode(param.thisObject);
    if(state==PNG){
     HookObjects.setObjectField(data,"mBitmapFormat",Bitmap.CompressFormat.PNG);
     MiuiHook.log("Editor export PNG HDR="+b.hasGainmap()+" "+b.getWidth()+"x"+b.getHeight()+" alpha="+b.hasAlpha());
    }else if(state==JPEG||b.hasGainmap()){
     Bitmap jpeg=ShellGainmap.flattenForJpeg(b);HookObjects.setObjectField(data,"image",jpeg);
     HookObjects.setObjectField(data,"mBitmapFormat",Bitmap.CompressFormat.JPEG);
     MiuiHook.log("Editor export Ultra HDR JPEG "+jpeg.getWidth()+"x"+jpeg.getHeight()+" alpha="+jpeg.hasAlpha());
    }
   }catch(Throwable t){MiuiHook.log("HDR export preparation failed: "+t);}}
  });
 }
}
