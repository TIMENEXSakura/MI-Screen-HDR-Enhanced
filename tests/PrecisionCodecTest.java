package dev.local.miuihdr;
import android.graphics.*;
import java.io.*;
import java.util.*;
import java.nio.ByteBuffer;
import android.os.Build;
/** Device test: JNI encoder, HDR container, fallback inputs and corruption rejection. */
public class PrecisionCodecTest {
 static int passed;
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);passed++;System.out.println("PASS "+why);}
 static Bitmap source(boolean alphaMap){
  Bitmap b=Bitmap.createBitmap(33,35,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.DISPLAY_P3));b.eraseColor(Color.rgb(170,180,190));
  Bitmap m=Bitmap.createBitmap(33,35,alphaMap?Bitmap.Config.ALPHA_8:Bitmap.Config.ARGB_8888);Canvas c=new Canvas(m);Paint p=new Paint();p.setColor(0xffc0c0c0);c.drawRect(16,0,17,35,p);Gainmap g=new Gainmap(m);g.setRatioMin(1,1,1);g.setRatioMax(4,4,4);g.setGamma(1,1,1);g.setEpsilonSdr(1f/64,1f/64,1f/64);g.setEpsilonHdr(1f/64,1f/64,1f/64);g.setMinDisplayRatioForHdrTransition(1);g.setDisplayRatioForFullHdr(4);b.setGainmap(g);return b;
 }
 static Bitmap field(ColorSpace.Named named,boolean alphaMap,boolean bright,boolean emptyMap){
  ColorSpace space=ColorSpace.get(named);
  Bitmap b=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888,false,space);b.eraseColor(Color.rgb(72,72,72));b.setDensity(420);
  Bitmap m=Bitmap.createBitmap(32,24,alphaMap?Bitmap.Config.ALPHA_8:Bitmap.Config.ARGB_8888);
  m.eraseColor(alphaMap?Color.TRANSPARENT:Color.BLACK);
  for(int y=8;y<16;y++)for(int x=8;x<24;x++){
   if(bright)b.setPixel(x,y,Color.rgb(220,220,220));
   if(!emptyMap)m.setPixel(x,y,alphaMap?Color.argb(bright?255:192,0,0,0):bright?Color.WHITE:Color.rgb(192,160,224));
  }
  Gainmap g=new Gainmap(m);g.setRatioMin(1,1,1);g.setRatioMax(2.5f,3,3.5f);g.setGamma(.8f,1.2f,.9f);
  g.setEpsilonSdr(1f/64,1f/80,1f/96);g.setEpsilonHdr(1f/64,1f/80,1f/96);
  g.setMinDisplayRatioForHdrTransition(1.125f);g.setDisplayRatioForFullHdr(4);
  if(Build.VERSION.SDK_INT>=36)g.setAlternativeImagePrimaries(space);
  b.setGainmap(g);return b;
 }
 static byte[] pixels(Bitmap b){ByteBuffer bytes=ByteBuffer.allocate(b.getByteCount());b.copyPixelsToBuffer(bytes);return bytes.array();}
 static float[] metadata(Gainmap g){
  float[] values=new float[17];float[][] groups={g.getRatioMin(),g.getRatioMax(),g.getGamma(),g.getEpsilonSdr(),g.getEpsilonHdr()};
  for(int i=0;i<groups.length;i++)System.arraycopy(groups[i],0,values,i*3,3);
  values[15]=g.getMinDisplayRatioForHdrTransition();values[16]=g.getDisplayRatioForFullHdr();return values;
 }
 static boolean metadataNear(Gainmap g,float[] expected,float tolerance){
  float[] actual=metadata(g);for(int i=0;i<actual.length;i++)if(!Float.isFinite(actual[i])||Math.abs(actual[i]-expected[i])>tolerance)return false;return true;
 }
 static final class Original {
  final Bitmap source,map;final byte[] basePixels,mapPixels;final float[] params;final ColorSpace space,alternative;final int density,direction;
  Original(Bitmap b){
   source=b;map=b.getGainmap().getGainmapContents();basePixels=pixels(b);mapPixels=pixels(map);params=metadata(b.getGainmap());space=b.getColorSpace();density=b.getDensity();
   alternative=Build.VERSION.SDK_INT>=36?b.getGainmap().getAlternativeImagePrimaries():null;direction=Build.VERSION.SDK_INT>=36?b.getGainmap().getGainmapDirection():0;
  }
  boolean unchanged(){
   if(source.isRecycled()||map.isRecycled()||!source.hasGainmap())return false;Gainmap g=source.getGainmap();
   return g.getGainmapContents()==map&&Arrays.equals(basePixels,pixels(source))&&Arrays.equals(mapPixels,pixels(map))&&Arrays.equals(params,metadata(g))&&
    space.equals(source.getColorSpace())&&density==source.getDensity()&&
    (Build.VERSION.SDK_INT<36||(Objects.equals(alternative,g.getAlternativeImagePrimaries())&&direction==g.getGainmapDirection()));
  }
 }
 static int signal(Bitmap m,int x,int y){int p=m.getPixel(x,y);return m.getConfig()==Bitmap.Config.ALPHA_8?Color.alpha(p):Math.max(Color.red(p),Math.max(Color.green(p),Color.blue(p)));}
 static boolean nonzero(Bitmap m){for(int y=0;y<m.getHeight();y++)for(int x=0;x<m.getWidth();x++)if(signal(m,x,y)>0)return true;return false;}
 // Android's fully-applied SDR-to-HDR gainmap equation, evaluated in the base's color space.
 static double sdr(Bitmap b,int x,int y,int channel){return ((ColorSpace.Rgb)b.getColorSpace()).getEotf().applyAsDouble(b.getColor(x,y).getComponents()[channel]);}
 static double hdr(Bitmap b,int x,int y,int channel){
  Gainmap g=b.getGainmap();Bitmap m=g.getGainmapContents();int p=m.getPixel(x,y);
  int q=m.getConfig()==Bitmap.Config.ALPHA_8?Color.alpha(p):(p>>(16-channel*8))&255;
  double factor=Math.exp(Math.log(g.getRatioMin()[channel])+(Math.log(g.getRatioMax()[channel])-Math.log(g.getRatioMin()[channel]))*Math.pow(q/255.0,g.getGamma()[channel]));
  return Math.max(0,(sdr(b,x,y,channel)+g.getEpsilonSdr()[channel])*factor-g.getEpsilonHdr()[channel]);
 }
 static double peakHdr(Bitmap b){double peak=0;for(int y=0;y<b.getHeight();y++)for(int x=0;x<b.getWidth();x++)for(int c=0;c<3;c++)peak=Math.max(peak,hdr(b,x,y,c));return peak;}
 static boolean onlyCenterHasGain(Bitmap m){
  for(int y=0;y<m.getHeight();y++)for(int x=0;x<m.getWidth();x++)if((signal(m,x,y)>0)!=(x>=8&&x<24&&y>=8&&y<16))return false;return true;
 }
 static void recycleHdr(Bitmap b){if(b==null||b.isRecycled())return;Bitmap m=b.hasGainmap()?b.getGainmap().getGainmapContents():null;b.recycle();if(m!=null&&!m.isRecycled())m.recycle();}
 static void darkPreservation(ColorSpace.Named space,boolean alpha)throws Exception{
  String label=space+" mapAlpha="+alpha;Bitmap in=field(space,alpha,false,false),decoded=null;Original original=new Original(in);
  try{
   check(nonzero(original.map)&&peakHdr(in)<.8,"dark fixture has real gain but every recovered channel stays below SDR white "+label);
   check(hdr(in,16,12,0)-sdr(in,16,12,0)>.02,"dark fixture contains an actual HDR/SDR difference "+label);
   Bitmap precise=GainmapPrecision.apply(in);
   check(precise==in,"zero-highlight precision returns the original bitmap "+label);
   check(original.unchanged()&&nonzero(original.map),"dark base pixels, gain pixels, density, primaries and all metadata remain exact "+label);
   boolean repeated=true;for(int i=0;i<6;i++)repeated&=GainmapPrecision.apply(precise)==in&&original.unchanged();
   check(repeated,"repeated dark precision never attenuates gain or recycles the original "+label);
   byte[] jpg=HdrJpeg444.encode(precise);decoded=BitmapFactory.decodeByteArray(jpg,0,jpg.length);
   check(decoded!=null&&decoded.hasGainmap()&&decoded.getWidth()==32&&decoded.getHeight()==24&&decoded.getGainmap().getGainmapContents().getWidth()==32&&decoded.getGainmap().getGainmapContents().getHeight()==24,"dark JPEG 4:4:4 roundtrip retains both pixel planes "+label);
   check(decoded.getColorSpace().equals(in.getColorSpace())&&metadataNear(decoded.getGainmap(),original.params,.0001f),"dark JPEG retains base primaries and gainmap parameters "+label);
   check(nonzero(decoded.getGainmap().getGainmapContents())&&HdrSignal.inspect(decoded).hdr,"dark JPEG is still recognized as a nonempty HDR image "+label);
   boolean radiance=true;for(int c=0;c<3;c++)radiance&=Math.abs(hdr(decoded,16,12,c)-hdr(in,16,12,c))<Math.max(.006,hdr(in,16,12,c)*.05)&&hdr(decoded,16,12,c)-sdr(decoded,16,12,c)>.02;
   check(radiance,"dark JPEG retains the original HDR/SDR separation within JPEG quantization "+label);
   Original roundtrip=new Original(decoded);boolean stable=true;
   for(int i=0;i<6;i++)stable&=GainmapPrecision.apply(decoded)==decoded&&roundtrip.unchanged();
   check(stable,"precision after JPEG decoding also keeps dark gain unchanged "+label);
   check(original.unchanged(),"JPEG encoding never mutates the dark source "+label);
  }finally{recycleHdr(decoded);recycleHdr(in);}
 }
 static void brightAndEmptyControls(ColorSpace.Named space,boolean alpha){
  String label=space+" mapAlpha="+alpha;Bitmap in=field(space,alpha,true,false),precise=null,empty=field(space,alpha,false,true);Original brightBefore=new Original(in),emptyBefore=new Original(empty);
  try{
   check(peakHdr(in)>1.5,"bright control genuinely exceeds SDR white "+label);
   precise=GainmapPrecision.apply(in);
   check(precise!=in&&precise.hasGainmap()&&onlyCenterHasGain(precise.getGainmap().getGainmapContents()),"bright precision still creates a localized nonempty gainmap "+label);
   boolean radiance=true;for(int c=0;c<3;c++)radiance&=Math.abs(hdr(precise,16,12,c)/hdr(in,16,12,c)-1)<.02;
   check(radiance&&brightBefore.unchanged(),"bright processing preserves reconstructed radiance and leaves its source untouched "+label);
   check(!nonzero(emptyBefore.map)&&GainmapPrecision.apply(empty)==empty&&emptyBefore.unchanged(),"a truly black gainmap stays black with its original metadata "+label);
   check(!HdrSignal.inspect(empty).hdr&&Math.abs(hdr(empty,16,12,0)-sdr(empty,16,12,0))<.000001,"an empty map does not acquire artificial HDR brightness "+label);
  }finally{if(precise!=in)recycleHdr(precise);recycleHdr(in);recycleHdr(empty);}
 }
 public static void main(String[] a){try{run(a);System.exit(0);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
 static void run(String[] a)throws Exception{
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
  Bitmap plain=Bitmap.createBitmap(3,3,Bitmap.Config.ARGB_8888);byte[] plainPixels=pixels(plain);
  check(GainmapPrecision.tryApply(plain)==plain&&!plain.hasGainmap()&&Arrays.equals(plainPixels,pixels(plain)),"SDR stays unchanged without fabricated gainmap");plain.recycle();
  for(ColorSpace.Named space:new ColorSpace.Named[]{ColorSpace.Named.DISPLAY_P3,ColorSpace.Named.SRGB})for(boolean alpha:new boolean[]{false,true}){
   darkPreservation(space,alpha);brightAndEmptyControls(space,alpha);
  }
  System.out.println("PRECISION_CODEC_CHECKS="+passed);
 }
}
