package dev.local.miuihdr;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import java.lang.reflect.Method;

/** Render the device's actual Xiaomi crop border, without changing its APK. */
public final class CropFrameAlphaTest {
 private static int checks;
 private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;System.out.println("PASS "+text);}
 private static Bitmap bitmap(){return Bitmap.createBitmap(192,256,Bitmap.Config.ARGB_8888);}
 private static Drawable crop(Resources resources,String name)throws Exception{
  int id=Class.forName("com.miui.mediaeditor.R$drawable").getField(name).getInt(null);
  Drawable drawable=resources.getDrawable(id,null).mutate();drawable.setBounds(24,24,168,232);
  return drawable;
 }
 private static void render(Drawable drawable,String label){
  Bitmap mask=bitmap();drawable.draw(new Canvas(mask));
  Bitmap base=bitmap();base.eraseColor(0xff344b63);
  Paint background=new Paint();background.setColor(0xff101820);new Canvas(base).drawRect(0,0,192,16,background);
  Bitmap map=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);map.eraseColor(Color.WHITE);
  Gainmap gain=new Gainmap(map);gain.setRatioMax(4,4,4);gain.setDisplayRatioForFullHdr(4);
  base.setGainmap(gain);
  long previous=-1;
  for(float fraction:new float[]{0,.25f,.5f,.75f,1}){
   // The real editor reads its HDR source into a window, then draws UI above it.
   // A mutable software Canvas target is not an HDR screenshot container: Canvas
   // may drop its gainmap. Do not mistake that unrelated operation for a UI fade.
   Bitmap result=bitmap();Canvas canvas=new Canvas(result);canvas.drawBitmap(base,0,0,null);
   try(CropFrameAlpha state=CropFrameAlpha.begin(drawable,fraction)){drawable.draw(canvas);}
   check(drawable.getAlpha()==255,label+" restores native alpha at "+fraction);
   boolean unchanged=true;long difference=0;int borderPixels=0;
   for(int y=0;y<256;y++)for(int x=0;x<192;x++){
    int a=base.getPixel(x,y),b=result.getPixel(x,y);
    if(Color.alpha(mask.getPixel(x,y))==0)unchanged&=a==b;else borderPixels++;
    difference+=Math.abs(Color.red(a)-Color.red(b))+Math.abs(Color.green(a)-Color.green(b))+Math.abs(Color.blue(a)-Color.blue(b));
   }
   check(borderPixels>0&&unchanged,label+" changes only the crop-border footprint at "+fraction);
   check(fraction==0?difference==0:difference>previous,label+" border progressively appears at "+fraction);
   check(base.hasGainmap()&&base.getGainmap().getDisplayRatioForFullHdr()==4
    &&base.getGainmap().getGainmapContents().getPixel(0,0)==Color.WHITE,label+" leaves original HDR source unchanged at "+fraction);
   previous=difference;
  }
 }
 public static void main(String[] args){try{run(args);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
 private static void run(String[] args)throws Exception{
  AssetManager assets=AssetManager.class.getDeclaredConstructor().newInstance();
  Method add=AssetManager.class.getDeclaredMethod("addAssetPath",String.class);add.setAccessible(true);
  check(((Integer)add.invoke(assets,args[0]))>0,"loaded original Xiaomi APK resources read-only");
  Resources system=Resources.getSystem();Resources resources=new Resources(assets,system.getDisplayMetrics(),system.getConfiguration());
  Drawable frame=crop(resources,"screen_crop_window");render(frame,"regular crop");
  render(crop(resources,"screen_long_crop_window"),"long crop");
  frame.setAlpha(180);
  try(CropFrameAlpha ignored=CropFrameAlpha.begin(frame,.5f)){check(frame.getAlpha()==90,"preserves native partial opacity");}
  check(frame.getAlpha()==180,"restores original nonopaque state");
  try(CropFrameAlpha ignored=CropFrameAlpha.begin(frame,0)){throw new IllegalStateException("simulated native draw failure");}
  catch(IllegalStateException expected){check(frame.getAlpha()==180,"restores crop border after native draw failure");}
  check(CropFrameAlpha.begin(frame,Float.NaN)==null&&frame.getAlpha()==180,"invalid fraction leaves native state unchanged");
  System.out.println("CROP_FRAME_ALPHA_CHECKS="+checks);
 }
}
