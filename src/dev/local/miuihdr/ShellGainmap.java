package dev.local.miuihdr;

import android.graphics.*;
import android.os.Build;
import java.lang.reflect.*;
import java.util.ArrayList;

/** Restores the enhancement layer after Xiaomi's software shell compositor. */
public final class ShellGainmap {
 private static Object field(Object o,String n)throws Exception {Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 private static float number(Object o,String n)throws Exception{return o.getClass().getField(n).getFloat(o);}
 private static Object call(Object o,String n,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(o,args);}
 public static boolean repair(Object entry,Bitmap source,Bitmap result)throws Exception {
  if(source==null||result==null||source==result||!source.hasGainmap())return false;
  if(!NativeBridge.loaded)throw new IllegalStateException("Native pixel compositor unavailable");
  Gainmap original=source.getGainmap();
  if(Build.VERSION.SDK_INT>=36&&original.getGainmapDirection()!=Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR)throw new IllegalArgumentException("HDR base direction unsupported by shell repair");
  ColorSpace space=source.getColorSpace(); if(!(space instanceof ColorSpace.Rgb))throw new IllegalArgumentException("Non-RGB source");
  ColorSpace alternative=Build.VERSION.SDK_INT>=36?original.getAlternativeImagePrimaries():null;
  if(alternative!=null&&!alternative.equals(space))throw new IllegalArgumentException("Different gainmap primaries need color conversion");
  Object info=call(entry,"getShellInfo",new Class<?>[0]);Bitmap shell=(Bitmap)field(entry,"mShellBitmap");
  if(info==null||shell==null)return false;
  int w=result.getWidth(),h=result.getHeight(); if((long)w*h>16000000L)throw new IllegalArgumentException("Shell output too large");
  float left=number(info,"leftMargin"),top=number(info,"topMargin"),right=number(info,"rightMargin"),bottom=number(info,"bottomMargin");
  float sw=((Number)info.getClass().getMethod("getShellWidth").invoke(info)).floatValue(),sh=((Number)info.getClass().getMethod("getShellHeight").invoke(info)).floatValue();
  RectF black=(RectF)call(entry,"getTopBlackViewRect",new Class<?>[]{RectF.class},new RectF(0,0,source.getWidth(),source.getHeight()));
  RectF dst=new RectF(left,top+black.height(),sw-right,sh-bottom);
  if(dst.isEmpty())throw new IllegalArgumentException("Empty shell screen geometry");
  Path path=(Path)call(entry,"getShellSvgPath",new Class<?>[0]);
  ArrayList<Bitmap> owned=new ArrayList<>();
  try {
   // Strip gainmap on a private copy: the SDR export produced by Xiaomi remains unchanged.
   Bitmap plain=source.copy(Bitmap.Config.ARGB_8888,false);owned.add(plain);plain.setGainmap(null);
   Bitmap base=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888,true,space);owned.add(base);
   Canvas bc=new Canvas(base); if(path!=null){Path translated=new Path(path);translated.offset(left,top);bc.clipPath(translated);}
   Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);bc.drawBitmap(plain,null,dst,p);
   Bitmap raw=original.getGainmapContents();
   Bitmap map=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);owned.add(map);
   Paint gp=new Paint(Paint.FILTER_BITMAP_FLAG);
   if(raw.getConfig()==Bitmap.Config.ALPHA_8)gp.setColorFilter(new ColorMatrixColorFilter(new float[]{0,0,0,1,0, 0,0,0,1,0, 0,0,0,1,0, 0,0,0,0,255}));
   new Canvas(map).drawBitmap(raw,null,dst,gp);
   Bitmap frame=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);owned.add(frame);
   new Canvas(frame).drawBitmap(shell,null,new RectF(0,0,sw,sh),p);
   attach(source,result,base,map,frame,owned);
   android.util.Log.i("MiuiHdrHook","Shell HDR restored "+w+"x"+h+" screen="+dst+" full="+original.getDisplayRatioForFullHdr());
   return true;
  }finally{for(Bitmap b:owned)b.recycle();}
 }
 public static boolean repairDraw(Object entry,Bitmap source,Bitmap result)throws Exception {
  if(source==null||result==null||!source.hasGainmap())return false;
  if(source.getWidth()!=result.getWidth()||source.getHeight()!=result.getHeight())throw new IllegalArgumentException("Editor draw geometry changed");
  java.util.Collection<?> nodes=(java.util.Collection<?>)field(entry,"mNodeList");
  if(nodes.isEmpty()){result.setGainmap(source.getGainmap());android.util.Log.i("MiuiHdrHook","Restored HDR after empty editor draw");return true;}
  if(!NativeBridge.loaded)throw new IllegalStateException("Native compositor unavailable");
  Gainmap gain=source.getGainmap();ColorSpace alternative=Build.VERSION.SDK_INT>=36?gain.getAlternativeImagePrimaries():null;
  if(alternative!=null&&!alternative.equals(source.getColorSpace()))throw new IllegalArgumentException("Different editor gainmap primaries");
  if(Build.VERSION.SDK_INT>=36&&gain.getGainmapDirection()!=Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR)throw new IllegalArgumentException("HDR base direction");
  ArrayList<Bitmap> owned=new ArrayList<>();
  try {
   Bitmap base=source;
   if(source.getConfig()!=Bitmap.Config.ARGB_8888){base=source.copy(Bitmap.Config.ARGB_8888,false);owned.add(base);}
   Bitmap map=Bitmap.createBitmap(result.getWidth(),result.getHeight(),Bitmap.Config.ARGB_8888);owned.add(map);
   Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);Bitmap raw=gain.getGainmapContents();
   if(raw.getConfig()==Bitmap.Config.ALPHA_8)paint.setColorFilter(new ColorMatrixColorFilter(new float[]{0,0,0,1,0, 0,0,0,1,0, 0,0,0,1,0, 0,0,0,0,255}));
   new Canvas(map).drawBitmap(raw,null,new RectF(0,0,result.getWidth(),result.getHeight()),paint);
   Bitmap overlay=(Bitmap)field(entry,"mErasableBitmap");
   if(overlay==null)throw new IllegalArgumentException("Missing editor overlay");
   attach(source,result,base,map,overlay,owned);
   android.util.Log.i("MiuiHdrHook","Restored HDR after editor annotations count="+nodes.size());return true;
  }finally{for(Bitmap b:owned)b.recycle();}
 }
 public static Bitmap flattenForJpeg(Bitmap source){
  if(!source.hasAlpha())return source;
  if(source.hasGainmap()&&!NativeBridge.loaded)throw new IllegalStateException("Native JPEG compositor unavailable");
  ArrayList<Bitmap> owned=new ArrayList<>();
  try{
   int w=source.getWidth(),h=source.getHeight();
   Bitmap plain=source.copy(Bitmap.Config.ARGB_8888,false);owned.add(plain);plain.setGainmap(null);
   Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888,false,source.getColorSpace());owned.add(result);
   Canvas canvas=new Canvas(result);canvas.drawColor(Color.WHITE);canvas.drawBitmap(plain,0,0,null);
   if(!source.hasGainmap()){owned.remove(result);return result;}
   Bitmap map=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);owned.add(map);Bitmap raw=source.getGainmap().getGainmapContents();
   Paint gp=new Paint(Paint.FILTER_BITMAP_FLAG);
   if(raw.getConfig()==Bitmap.Config.ALPHA_8)gp.setColorFilter(new ColorMatrixColorFilter(new float[]{0,0,0,1,0, 0,0,0,1,0, 0,0,0,1,0, 0,0,0,0,255}));
   new Canvas(map).drawBitmap(raw,null,new RectF(0,0,w,h),gp);
   Bitmap transparent=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);owned.add(transparent);
   attach(source,result,plain,map,transparent,owned);owned.remove(result);
   return result;
  }finally{for(Bitmap b:owned)b.recycle();}
 }
 private static void attach(Bitmap source,Bitmap result,Bitmap base,Bitmap map,Bitmap frame,ArrayList<Bitmap> owned){
   int w=result.getWidth(),h=result.getHeight();Gainmap original=source.getGainmap();ColorSpace space=source.getColorSpace();
   ColorSpace alternative=Build.VERSION.SDK_INT>=36?original.getAlternativeImagePrimaries():null;
   Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);owned.add(out);
   float[] params=new float[21];float[][] values={original.getRatioMin(),original.getRatioMax(),original.getGamma(),original.getEpsilonSdr(),original.getEpsilonHdr()};
   for(int i=0;i<5;i++)System.arraycopy(values[i],0,params,i*3,3);
   float[] transfer=new float[256];java.util.function.DoubleUnaryOperator eotf=((ColorSpace.Rgb)space).getEotf();
   for(int i=0;i<256;i++)transfer[i]=(float)eotf.applyAsDouble(i/255.0);
   if(!NativeBridge.composeShellGainmap(base,map,frame,result,out,params,transfer))throw new IllegalStateException("Native shell composition failed");
   Gainmap restored=new Gainmap(out);restored.setRatioMin(params[15],params[16],params[17]);restored.setRatioMax(params[18],params[19],params[20]);
   restored.setGamma(1,1,1);restored.setEpsilonSdr(1f/64,1f/64,1f/64);restored.setEpsilonHdr(1f/64,1f/64,1f/64);
   restored.setMinDisplayRatioForHdrTransition(original.getMinDisplayRatioForHdrTransition());restored.setDisplayRatioForFullHdr(original.getDisplayRatioForFullHdr());
   if(Build.VERSION.SDK_INT>=36)restored.setAlternativeImagePrimaries(alternative);
   result.setGainmap(restored);owned.remove(out);
 }

}
