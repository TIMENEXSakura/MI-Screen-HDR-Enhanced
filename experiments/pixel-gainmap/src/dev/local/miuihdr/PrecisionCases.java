package dev.local.miuihdr;
import android.graphics.*;
import java.io.*;
import java.nio.*;
import java.util.*;
public class PrecisionCases {
 static int passed=0;
 static void check(boolean c,String s){if(!c)throw new AssertionError(s);System.out.println("PASS "+s);passed++;}
 static byte[] bytes(Bitmap b){ByteBuffer out=ByteBuffer.allocate(b.getByteCount());b.copyPixelsToBuffer(out);return out.array();}
 static double[] radiance(Bitmap b,int x,int y){
  Gainmap g=b.getGainmap();Bitmap m=g.getGainmapContents();int bv=b.getPixel(x,y),mv=m.getPixel(x,y);float[] colors=b.getColor(x,y).getComponents();double[] out=new double[3];
  for(int c=0;c<3;c++){int v=m.getConfig()==Bitmap.Config.ALPHA_8?mv>>>24:mv>>(16-8*c)&255;double linear=((ColorSpace.Rgb)b.getColorSpace()).getEotf().applyAsDouble(colors[c]);double L=Math.log(g.getRatioMin()[c])+(Math.log(g.getRatioMax()[c])-Math.log(g.getRatioMin()[c]))*Math.pow(v/255.,g.getGamma()[c]);out[c]=(linear+g.getEpsilonSdr()[c])*Math.exp(L)-g.getEpsilonHdr()[c];}return out;
 }
 static Bitmap fixture(ColorSpace space,boolean a8,boolean mixed){
  int w=257,h=259;Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888,false,space),m=Bitmap.createBitmap(w,h,a8?Bitmap.Config.ALPHA_8:Bitmap.Config.ARGB_8888);
  ByteBuffer base=ByteBuffer.allocate(b.getByteCount()),gain=ByteBuffer.allocate(m.getByteCount());
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){
   boolean hot=(x==128||y==128||x==11&&y==19);int p=y*b.getRowBytes()+x*4;
   for(int c=0;c<3;c++)base.put(p+c,(byte)(hot?new int[]{40,180,240}[c]:new int[]{70,100,140}[c]));base.put(p+3,(byte)255);
   int q=y*m.getRowBytes()+x*(a8?1:4);if(a8)gain.put(q,(byte)(hot?230:16));else{for(int c=0;c<3;c++)gain.put(q+c,(byte)(hot?new int[]{128,190,220}[c]:16));gain.put(q+3,(byte)255);}
  }
  b.copyPixelsFromBuffer(base);m.copyPixelsFromBuffer(gain);Gainmap gm=new Gainmap(m);
  gm.setRatioMin(mixed?.8f:1,1,1);gm.setRatioMax(mixed?2:4,4,mixed?8:4);gm.setGamma(mixed?1.8f:1,1,mixed?.7f:1);
  gm.setEpsilonSdr(mixed?.02f:1f/64,1f/64,1f/64);gm.setEpsilonHdr(mixed?.01f:1f/64,1f/64,1f/64);gm.setMinDisplayRatioForHdrTransition(1);gm.setDisplayRatioForFullHdr(mixed?8:4);b.setGainmap(gm);return b;
 }
 static void run(String name,Bitmap in,String folder)throws Exception{
  try(FileOutputStream f=new FileOutputStream(folder+"/"+name+"-original.jpg")){in.compress(Bitmap.CompressFormat.JPEG,100,f);}Bitmap originalJpeg=BitmapFactory.decodeFile(folder+"/"+name+"-original.jpg");PrecisionProbe.dump(originalJpeg,folder+"/"+name+"-original-jpg");originalJpeg.recycle();
  Bitmap out=GainmapPrecision.apply(in);check(out!=in&&out.hasGainmap(),name+" processed");PrecisionProbe.dump(in,folder+"/"+name+"-input");PrecisionProbe.dump(out,folder+"/"+name+"-memory");
  Bitmap cur=out;for(int i=0;i<10;i++){Bitmap next=GainmapPrecision.apply(cur);if(cur!=out)cur.recycle();cur=next;}PrecisionProbe.dump(cur,folder+"/"+name+"-repeat10");cur.recycle();
  for(String ext:new String[]{"png","jpg"}){String path=folder+"/"+name+"."+ext;try(FileOutputStream f=new FileOutputStream(path)){check(out.compress(ext.equals("png")?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,100,f),name+" "+ext+" encode");}Bitmap r=BitmapFactory.decodeFile(path);check(r!=null&&r.hasGainmap(),name+" "+ext+" decode HDR");PrecisionProbe.dump(r,folder+"/"+name+"-"+ext);r.recycle();}out.recycle();in.recycle();
 }
 public static void main(String[] a)throws Exception{
  System.load(a[0]);NativeBridge.loaded=true;
  for(boolean p3:new boolean[]{false,true})for(boolean alpha:new boolean[]{false,true})for(boolean mixed:new boolean[]{false,true})run((p3?"p3":"srgb")+(alpha?"-a8":"-rgb")+(mixed?"-mixed":"-uniform"),fixture(ColorSpace.get(p3?ColorSpace.Named.DISPLAY_P3:ColorSpace.Named.SRGB),alpha,mixed),a[1]);
  Bitmap plain=Bitmap.createBitmap(10,10,Bitmap.Config.ARGB_8888);check(GainmapPrecision.apply(plain)==plain,"SDR pass through");plain.recycle();
  Bitmap low=fixture(ColorSpace.get(ColorSpace.Named.SRGB),false,false);low.setGainmap(new Gainmap(Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)));boolean fail=false;try{GainmapPrecision.apply(low);}catch(IllegalArgumentException e){fail=true;}check(fail&&low.hasGainmap(),"reject low resolution without mutating input");low.recycle();
  Bitmap transparent=fixture(ColorSpace.get(ColorSpace.Named.SRGB),false,false);transparent.setHasAlpha(true);transparent.setPixel(0,0,0x00776655);fail=false;try{GainmapPrecision.apply(transparent);}catch(IllegalStateException e){fail=true;}check(fail&&transparent.hasGainmap()&&transparent.getPixel(0,0)==0,"reject transparency without mutating input");transparent.recycle();
  // Every 8-bit base / scalar gain pair near SDR white. This measures the
  // unavoidable uncertainty band instead of pretending all weak HDR is known.
  Bitmap ramp=Bitmap.createBitmap(256,128,Bitmap.Config.ARGB_8888),rampMap=Bitmap.createBitmap(256,128,Bitmap.Config.ARGB_8888);
  for(int y=0;y<128;y++)for(int x=0;x<256;x++){ramp.setPixel(x,y,Color.rgb(y+128,y+128,y+128));rampMap.setPixel(x,y,Color.rgb(x,x,x));}
  Gainmap rg=new Gainmap(rampMap);rg.setRatioMin(1,1,1);rg.setRatioMax(4,4,4);rg.setGamma(1,1,1);rg.setEpsilonSdr(1f/64,1f/64,1f/64);rg.setEpsilonHdr(1f/64,1f/64,1f/64);rg.setMinDisplayRatioForHdrTransition(1);rg.setDisplayRatioForFullHdr(4);ramp.setGainmap(rg);run("white-ramp",ramp,a[1]);
  System.out.println("CASE_CHECKS="+passed);
 }
}
