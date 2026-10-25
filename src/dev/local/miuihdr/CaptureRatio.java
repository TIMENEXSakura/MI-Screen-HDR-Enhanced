package dev.local.miuihdr;

import android.graphics.Bitmap;
import android.graphics.Gainmap;
import android.os.Build;
import java.util.Arrays;
import java.util.WeakHashMap;

/** Brightness metadata only. Never invent or resample spatial HDR information. */
final class CaptureRatio {
 static final String AUTO="auto", OFFSET="offset", FORCE="force";
 private static final WeakHashMap<Bitmap,Original> ORIGINALS=new WeakHashMap<>();
 private static final class Original {
  final Gainmap input,metadata;
  final float nativeRatio;
  Gainmap last;
  Original(Gainmap gainmap,float ratio){input=gainmap;metadata=copy(gainmap);nativeRatio=ratio;last=gainmap;}
 }
 /** Native/display 1x is not evidence of usable HDR headroom. */
 static boolean valid(float value){return Float.isFinite(value)&&value>1&&value<=10000;}
 static boolean validForced(float value){return Float.isFinite(value)&&value>=1&&value<=10000;}
 static float choose(float nativeRatio,float displayRatio,float manual){
  return choose(nativeRatio,displayRatio,validForced(manual)?FORCE:AUTO,0,manual);
 }
 static float choose(float nativeRatio,float displayRatio,String mode,float offset,float forced){
  if(FORCE.equals(mode))return validForced(forced)?forced:Float.NaN;
  float baseline=valid(nativeRatio)?nativeRatio:valid(displayRatio)?displayRatio:Float.NaN;
  if(OFFSET.equals(mode)){
   if(!Float.isFinite(baseline)||!Float.isFinite(offset))return Float.NaN;
   return (float)Math.max(1,Math.min(10000,(double)baseline+offset));
  }
  return Float.isFinite(baseline)?baseline:1;
 }
 static boolean apply(Bitmap bitmap,float nativeRatio,float displayRatio,float manual){
  return apply(bitmap,nativeRatio,displayRatio,validForced(manual)?FORCE:AUTO,0,manual);
 }
 static boolean apply(Bitmap bitmap,float nativeRatio,float displayRatio,String mode,float offset,float forced){
  if(bitmap==null||bitmap.isRecycled()||!bitmap.hasGainmap())return false;
  synchronized(ORIGINALS){
   Gainmap current=bitmap.getGainmap();
   Original saved=ORIGINALS.get(bitmap);
   // If another pipeline supplies a new gainmap, it becomes the new source.
   if(saved!=null&&current!=saved.last&&current!=saved.input){ORIGINALS.remove(bitmap);saved=null;}
   float baseline=saved==null?nativeRatio:saved.nativeRatio;
   float selected=choose(baseline,displayRatio,mode,offset,forced);
   if(!validForced(selected))return false;
   if(!FORCE.equals(mode)&&!valid(baseline)&&!valid(displayRatio))return false;
   boolean preserveNative=valid(baseline)&&(AUTO.equals(mode)||(OFFSET.equals(mode)&&offset==0));
   if(preserveNative&&saved==null)return false;
   Gainmap original=saved==null?current:saved.metadata;
   // Screenshot gainmaps use an SDR base. Reinterpreting an HDR base would reverse the math.
   if(Build.VERSION.SDK_INT>=36&&original.getGainmapDirection()!=Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR)return false;
   if(!validMetadata(original))return false;
   Gainmap updated=copy(original);
   if(!preserveNative)adjust(updated,selected);
   if(sameMetadata(current,updated))return false;
   if(saved==null){saved=new Original(current,nativeRatio);ORIGINALS.put(bitmap,saved);}
   // Bitmap may recreate its Java Gainmap wrapper after setGainmap; retain the attached one.
   bitmap.setGainmap(updated);saved.last=bitmap.getGainmap();
   HdrSignal.invalidate(bitmap);
   return true;
  }
 }
 private static void adjust(Gainmap updated,float selected){
  float[] min=updated.getRatioMin(),max=updated.getRatioMax();
  if(selected==1){
   // A true 1x setting must not leave an epsilon-induced brightness offset.
   updated.setRatioMin(1,1,1);updated.setRatioMax(1,1,1);
   float[] epsilon=updated.getEpsilonSdr();updated.setEpsilonHdr(epsilon[0],epsilon[1],epsilon[2]);
   updated.setMinDisplayRatioForHdrTransition(1);
  }else{
   float peak=Math.max(max[0],Math.max(max[1],max[2]));
   double scale=peak>1?Math.log(selected)/Math.log(peak):Double.NaN;
   for(int i=0;i<3;i++){
    // Preserve relative channel strengths in log space; never lift the black-map endpoint.
    max[i]=Double.isFinite(scale)?(float)Math.exp(Math.log(max[i])*scale):selected;
    max[i]=Math.max(Float.MIN_NORMAL,Math.min(selected,max[i]));
    min[i]=Math.min(min[i],max[i]);
   }
   updated.setRatioMin(min[0],min[1],min[2]);updated.setRatioMax(max[0],max[1],max[2]);
   float transition=updated.getMinDisplayRatioForHdrTransition();
   // Keep a nonempty transition interval when the requested peak is reduced below it.
   updated.setMinDisplayRatioForHdrTransition(Math.min(transition,Math.nextDown(selected)));
  }
  updated.setDisplayRatioForFullHdr(selected);
 }
 private static boolean validMetadata(Gainmap value){
  float[] min=value.getRatioMin(),max=value.getRatioMax(),gamma=value.getGamma();
  float[] sdr=value.getEpsilonSdr(),hdr=value.getEpsilonHdr();
  for(int i=0;i<3;i++)if(!Float.isFinite(min[i])||min[i]<=0||!Float.isFinite(max[i])||max[i]<min[i]
    ||!Float.isFinite(gamma[i])||gamma[i]<=0||!Float.isFinite(sdr[i])||sdr[i]<0||!Float.isFinite(hdr[i])||hdr[i]<0)return false;
  float start=value.getMinDisplayRatioForHdrTransition(),end=value.getDisplayRatioForFullHdr();
  return Float.isFinite(start)&&start>=1&&Float.isFinite(end)&&end>=start;
 }
 private static boolean sameMetadata(Gainmap a,Gainmap b){
  return Arrays.equals(a.getRatioMin(),b.getRatioMin())&&Arrays.equals(a.getRatioMax(),b.getRatioMax())
   &&Arrays.equals(a.getEpsilonHdr(),b.getEpsilonHdr())
   &&a.getDisplayRatioForFullHdr()==b.getDisplayRatioForFullHdr()
   &&a.getMinDisplayRatioForHdrTransition()==b.getMinDisplayRatioForHdrTransition();
 }
 private static Gainmap copy(Gainmap source){
  if(Build.VERSION.SDK_INT>=35)return new Gainmap(source,source.getGainmapContents());
  Gainmap copy=new Gainmap(source.getGainmapContents());
  float[] v=source.getRatioMin();copy.setRatioMin(v[0],v[1],v[2]);
  v=source.getRatioMax();copy.setRatioMax(v[0],v[1],v[2]);
  v=source.getGamma();copy.setGamma(v[0],v[1],v[2]);
  v=source.getEpsilonSdr();copy.setEpsilonSdr(v[0],v[1],v[2]);
  v=source.getEpsilonHdr();copy.setEpsilonHdr(v[0],v[1],v[2]);
  copy.setMinDisplayRatioForHdrTransition(source.getMinDisplayRatioForHdrTransition());
  copy.setDisplayRatioForFullHdr(source.getDisplayRatioForFullHdr());
  return copy;
 }
}
