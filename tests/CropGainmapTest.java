package dev.local.miuihdr;
import android.graphics.*;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;

/** Run against the device's real Xiaomi crop classes, including a second save. */
public final class CropGainmapTest {
 static int count;
 static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);count++;System.out.println("PASS "+text);}
 static Bitmap source(){
  Bitmap b=Bitmap.createBitmap(48,60,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.DISPLAY_P3));b.eraseColor(Color.GRAY);
  Bitmap m=Bitmap.createBitmap(48,60,Bitmap.Config.ARGB_8888);m.eraseColor(Color.BLACK);m.setPixel(17,21,Color.WHITE);
  Gainmap g=new Gainmap(m);g.setRatioMin(1,1,1);g.setRatioMax(4,4,4);g.setGamma(1,1,1);g.setEpsilonSdr(.015625f,.015625f,.015625f);g.setEpsilonHdr(.015625f,.015625f,.015625f);g.setMinDisplayRatioForHdrTransition(1);g.setDisplayRatioForFullHdr(4);b.setGainmap(g);return b;
 }
 static Bitmap crop(Bitmap src,RectF selection,RectF bitmapArea)throws Exception{
  Class<?> cls=Class.forName("com.miui.gallery.editor.photo.screen.crop.ScreenCropEntry");
  Object entry=cls.getConstructor(RectF.class,RectF.class,float.class).newInstance(selection,bitmapArea,0f);
  Bitmap out=(Bitmap)cls.getMethod("apply",Bitmap.class).invoke(entry,src);
  check(!out.hasGainmap(),"reproduced gainmap loss in Xiaomi crop Canvas");
  Field f=cls.getDeclaredField("mCropArea");f.setAccessible(true);RectF r=(RectF)f.get(entry);
  int[] before=new int[out.getWidth()*out.getHeight()];out.getPixels(before,0,out.getWidth(),0,0,out.getWidth(),out.getHeight());
  Bitmap repaired=CropGainmap.restore(src,out,(int)(r.left+.5f),(int)(r.top+.5f),(int)(r.width()+.5f),(int)(r.height()+.5f));
  check(repaired.hasGainmap(),"cropped output retains HDR");
  check(repaired.getColorSpace().equals(src.getColorSpace()),"cropped color space retained");
  int[] after=new int[before.length];repaired.getPixels(after,0,out.getWidth(),0,0,out.getWidth(),out.getHeight());
  check(java.util.Arrays.equals(before,after),"regular crop base pixels unchanged");
  return repaired;
 }
 static Bitmap save(Bitmap b){
  ByteArrayOutputStream output=new ByteArrayOutputStream();check(b.compress(Bitmap.CompressFormat.JPEG,100,output),"JPEG save completed");
  byte[] data=output.toByteArray();Bitmap decoded=BitmapFactory.decodeByteArray(data,0,data.length);
  check(decoded!=null&&decoded.hasGainmap(),"saved JPEG reopens with HDR");
  check(decoded.getGainmap().getDisplayRatioForFullHdr()==4,"save keeps original headroom");return decoded;
 }
 public static void main(String[] args)throws Exception{
  Bitmap source=source();Bitmap cropped=crop(source,new RectF(110,220,170,300),new RectF(100,200,196,320));
  check(cropped.getWidth()==30&&cropped.getHeight()==40,"view coordinates mapped to actual source crop");
  Bitmap map=cropped.getGainmap().getGainmapContents();boolean exact=map.getWidth()==30&&map.getHeight()==40;
  for(int y=0;y<40&&exact;y++)for(int x=0;x<30;x++)exact&=map.getPixel(x,y)==source.getGainmap().getGainmapContents().getPixel(x+5,y+10);
  check(exact,"single bright gain pixel keeps exact position without expansion");
  Bitmap first=save(cropped);Bitmap twice=crop(first,new RectF(3,4,23,34),new RectF(0,0,30,40));Bitmap second=save(twice);
  check(second.getWidth()==20&&second.getHeight()==30,"second crop and save keep dimensions and HDR");
  Class<?> longType=Class.forName("com.miui.gallery.editor.photo.core.imports.longcrop.LongScreenshotCropEditorView$Entry");
  Object entry=longType.getConstructor(float.class,float.class).newInstance(.25f,.75f);
  Bitmap longOut=(Bitmap)longType.getMethod("apply",Bitmap.class).invoke(entry,source);
  check(!longOut.hasGainmap(),"reproduced long crop gainmap loss");
  Bitmap longHdr=CropGainmap.restore(source,longOut,0,15,48,30);
  check(longHdr.hasGainmap()&&longHdr.getColorSpace().equals(source.getColorSpace()),"long crop restores HDR and original P3 base");
  Bitmap unchanged=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);
  check(CropGainmap.restore(source,unchanged,-1,0,2,2)==unchanged&&!unchanged.hasGainmap(),"invalid geometry leaves native result untouched");
  check(CropGainmap.restore(source,unchanged,0,0,3,2)==unchanged&&!unchanged.hasGainmap(),"dimension mismatch does not stretch gainmap");
  check(source.getGainmap().getGainmapContents().getPixel(17,21)==Color.WHITE,"original image gainmap unchanged");
  System.out.println("CROP_GAINMAP_CHECKS="+count);
 }
}
