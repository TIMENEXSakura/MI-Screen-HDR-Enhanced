package dev.local.miuihdr;
import android.graphics.*;
import java.io.*;
import java.util.*;
/** Device test: JNI encoder, HDR container, fallback inputs and corruption rejection. */
public class PrecisionCodecTest {
 static int passed;
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);passed++;System.out.println("PASS "+why);}
 static Bitmap source(boolean alphaMap){
  Bitmap b=Bitmap.createBitmap(33,35,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.DISPLAY_P3));b.eraseColor(Color.rgb(170,180,190));
  Bitmap m=Bitmap.createBitmap(33,35,alphaMap?Bitmap.Config.ALPHA_8:Bitmap.Config.ARGB_8888);Canvas c=new Canvas(m);Paint p=new Paint();p.setColor(0xffc0c0c0);c.drawRect(16,0,17,35,p);Gainmap g=new Gainmap(m);g.setRatioMin(1,1,1);g.setRatioMax(4,4,4);g.setGamma(1,1,1);g.setEpsilonSdr(1f/64,1f/64,1f/64);g.setEpsilonHdr(1f/64,1f/64,1f/64);g.setMinDisplayRatioForHdrTransition(1);g.setDisplayRatioForFullHdr(4);b.setGainmap(g);return b;
 }
 public static void main(String[] a)throws Exception{
  System.load(a[0]);NativeBridge.loaded=true;
  for(boolean alpha:new boolean[]{false,true}){
   Bitmap in=source(alpha);byte[] jpg=HdrJpeg444.encode(in);Bitmap decoded=BitmapFactory.decodeByteArray(jpg,0,jpg.length);
   check(decoded!=null&&decoded.hasGainmap(),"HDR decode mapAlpha="+alpha);
   check(decoded.getWidth()==33&&decoded.getHeight()==35&&decoded.getGainmap().getGainmapContents().getWidth()==33&&decoded.getGainmap().getGainmapContents().getHeight()==35,"both planes keep size");
   check(decoded.getColorSpace().equals(in.getColorSpace()),"Display P3 retained");check(decoded.getGainmap().getDisplayRatioForFullHdr()==4,"HDR display headroom retained");
   ByteArrayOutputStream plain=new ByteArrayOutputStream();Bitmap sdr=in.copy(Bitmap.Config.ARGB_8888,false);sdr.setGainmap(null);sdr.compress(Bitmap.CompressFormat.JPEG,100,plain);byte[] base=plain.toByteArray();
   for(byte[] bad:new byte[][]{new byte[0],Arrays.copyOf(jpg,20),Arrays.copyOf(jpg,jpg.length-1),base}){
    boolean rejected=false;try{HdrJpeg444.repack(bad,base,base);}catch(IOException e){rejected=true;}check(rejected,"malformed/missing HDR metadata rejected length="+bad.length);
   }
   check(in.hasGainmap(),"original HDR kept on failures");in.recycle();decoded.recycle();sdr.recycle();
  }
  Bitmap transparent=source(false);transparent.setHasAlpha(true);transparent.setPixel(0,0,0);
  check(GainmapPrecision.tryApply(transparent)==transparent,"transparent precision input falls back unchanged");
  boolean rejected=false;try{HdrJpeg444.encode(transparent);}catch(IOException e){rejected=true;}check(rejected&&transparent.hasGainmap(),"transparent JPEG input rejected without input mutation");transparent.recycle();
  Bitmap low=source(false);low.setGainmap(new Gainmap(Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)));check(GainmapPrecision.tryApply(low)==low,"low-resolution gain map fallback");low.recycle();
  Bitmap plain=Bitmap.createBitmap(3,3,Bitmap.Config.ARGB_8888);check(GainmapPrecision.tryApply(plain)==plain,"SDR unchanged");plain.recycle();
  System.out.println("PRECISION_CODEC_CHECKS="+passed);
 }
}
