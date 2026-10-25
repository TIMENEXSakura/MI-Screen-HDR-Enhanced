package dev.local.miuihdr;

import android.graphics.Bitmap;
import android.graphics.RectF;

final class CropHooks {
 static void install(ClassLoader loader){
  for(String name:new String[]{"com.miui.gallery.editor.photo.screen.crop.ScreenCropEntry","com.miui.gallery.editor.photo.core.imports.longcrop.LongScreenshotCropEditorView$Entry"})try{
   boolean longCrop=name.endsWith("$Entry");
   HookRuntime.add(loader.loadClass(name).getDeclaredMethod("apply",Bitmap.class),new HookRuntime.Callback(){
    protected void after(HookRuntime.Call p){
     if(p.hasThrowable())return;
     try{
      Bitmap source=(Bitmap)p.args[0],result=(Bitmap)p.getResult();
      if(source==null||result==null||!source.hasGainmap()||result.hasGainmap())return;
      int left,top,width,height;
      if(longCrop){
       float start=((Number)EditorPreviewHdr.field(p.thisObject,"mTopRatio")).floatValue();
       float end=((Number)EditorPreviewHdr.field(p.thisObject,"mBottomRatio")).floatValue();
       if(!Float.isFinite(start)||!Float.isFinite(end))return;
       left=0;top=(int)(source.getHeight()*start+.5f);width=source.getWidth();height=(int)(source.getHeight()*end+.5f)-top;
      }else{
       // apply() has already mapped this rectangle into source-pixel coordinates.
       RectF rect=(RectF)EditorPreviewHdr.field(p.thisObject,"mCropArea");
       if(rect==null||!Float.isFinite(rect.left)||!Float.isFinite(rect.top)||!Float.isFinite(rect.right)||!Float.isFinite(rect.bottom))return;
       left=(int)(rect.left+.5f);top=(int)(rect.top+.5f);width=(int)(rect.width()+.5f);height=(int)(rect.height()+.5f);
      }
      Bitmap restored=CropGainmap.restore(source,result,left,top,width,height);
      HdrSignal.invalidate(restored);
      if(restored!=result)p.setResult(restored);
      MiuiHook.log("Editor crop HDR="+restored.hasGainmap()+" crop="+left+","+top+","+width+","+height);
     }catch(Throwable t){MiuiHook.log("Crop gainmap kept native output on unsupported geometry: "+t);}
    }
   });
  }catch(Throwable t){MiuiHook.log("Crop hook unavailable "+name+": "+t);}
 }
}
