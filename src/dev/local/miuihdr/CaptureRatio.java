package dev.local.miuihdr;
import android.graphics.*;

/** Brightness metadata only. Never invent spatial HDR information. */
final class CaptureRatio {
 static boolean valid(float value){return Float.isFinite(value)&&value>1&&value<=10000;}
 static float choose(float nativeRatio,float displayRatio,float manual){
  if(valid(manual))return manual;
  if(valid(nativeRatio))return nativeRatio;
  return valid(displayRatio)?displayRatio:1;
 }
 static boolean apply(Bitmap bitmap,float nativeRatio,float displayRatio,float manual){
  if(bitmap==null||!bitmap.hasGainmap())return false;
  float selected=choose(nativeRatio,displayRatio,manual);
  if(!valid(selected)||Math.abs(selected-nativeRatio)<0.001f)return false;
  Gainmap old=bitmap.getGainmap();
  Gainmap updated=new Gainmap(old,old.getGainmapContents());
  updated.setRatioMax(selected,selected,selected);
  updated.setDisplayRatioForFullHdr(selected);
  bitmap.setGainmap(updated);HdrSignal.invalidate(bitmap);return true;
 }
}
