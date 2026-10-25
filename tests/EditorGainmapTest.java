package dev.local.miuihdr;
import android.graphics.*;
public final class EditorGainmapTest {
 static int passed;
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);passed++;System.out.println("PASS "+message);}
 public static void main(String[] args){
  Bitmap source=Bitmap.createBitmap(13,11,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.DISPLAY_P3));source.eraseColor(Color.GRAY);
  Bitmap map=Bitmap.createBitmap(13,11,Bitmap.Config.ARGB_8888);map.eraseColor(Color.BLACK);map.setPixel(4,6,Color.WHITE);
  Gainmap gm=new Gainmap(map);gm.setRatioMin(1,1,1);gm.setRatioMax(4,4,4);gm.setGamma(1,1,1);gm.setEpsilonSdr(.015625f,.015625f,.015625f);gm.setEpsilonHdr(.015625f,.015625f,.015625f);gm.setMinDisplayRatioForHdrTransition(1);gm.setDisplayRatioForFullHdr(4);source.setGainmap(gm);
  Bitmap full=Bitmap.createBitmap(13,11,Bitmap.Config.ARGB_8888);full.eraseColor(Color.GRAY);
  check(EditorGainmap.restore(source,full,0)&&full.hasGainmap(),"full editor view retains gainmap");
  check(full.getPixel(0,0)==Color.GRAY,"editor restoration leaves base pixels unchanged");
  check(full.getGainmap().getDisplayRatioForFullHdr()==4,"editor retains HDR headroom");
  Bitmap crop=Bitmap.createBitmap(13,5,Bitmap.Config.ARGB_8888);
  check(EditorGainmap.restore(source,crop,3),"long-crop gainmap restored");
  Bitmap cm=crop.getGainmap().getGainmapContents();
  check(cm.getWidth()==13&&cm.getHeight()==5,"gainmap uses identical crop geometry");
  boolean aligned=true;for(int y=0;y<5;y++)for(int x=0;x<13;x++)aligned&=cm.getPixel(x,y)==map.getPixel(x,y+3);
  check(aligned,"single bright pixel remains at its exact cropped location");
  check(!EditorGainmap.restore(source,crop,-1)&&!EditorGainmap.restore(source,crop,9),"invalid crop rejected");
  Bitmap mismatched=Bitmap.createBitmap(3,5,Bitmap.Config.ARGB_8888);check(!EditorGainmap.restore(source,mismatched,0),"unsupported scale rejected");
  Bitmap plain=source.copy(Bitmap.Config.ARGB_8888,false);plain.setGainmap(null);
  check(!EditorGainmap.restore(plain,full,0)&&!full.hasGainmap(),"SDR edit clears stale gainmap");
  check(CaptureRatio.choose(4,8,0)==4,"valid native brightness metadata preserved");
  check(CaptureRatio.choose(1,6,0)==6,"missing native ratio uses current display");
  check(CaptureRatio.choose(Float.NaN,Float.NaN,0)==1,"unavailable ratios do not invent HDR");
  check(CaptureRatio.choose(4,8,2)==2,"manual ratio overrides metadata");
  check(!CaptureRatio.apply(plain,1,4,0)&&!plain.hasGainmap(),"ratio cannot create a missing gainmap");
  check(CaptureRatio.apply(source,4,8,2)&&source.getGainmap().getDisplayRatioForFullHdr()==2,"manual ratio changes brightness metadata");
  Bitmap unchanged=source.getGainmap().getGainmapContents();boolean same=true;
  for(int y=0;y<11;y++)for(int x=0;x<13;x++)same&=unchanged.getPixel(x,y)==map.getPixel(x,y);
  check(same,"ratio adjustment preserves every gainmap pixel");
  check(gm.getDisplayRatioForFullHdr()==4,"shared original metadata not mutated");
  System.out.println("EDITOR_GAINMAP_CHECKS="+passed);
 }
}
