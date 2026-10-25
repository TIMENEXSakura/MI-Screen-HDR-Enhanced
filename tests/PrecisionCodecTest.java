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
 // Skia ISO 21496-1 uses nearest rationals: denominator 2^12 for |x|>1,
 // otherwise 2^28. Ratio/headroom store log2(value); gamma stores 1/value.
 // Source: https://skia.googlesource.com/skia/+/4609099110c3ea6a6e09866432fd4166eef71ab3/src/codec/SkGainmapInfo.cpp
 // Bounds propagate half a rational step through the inverse transform, with
 // float ULP allowances for the encoder/decoder arithmetic, separately per field.
 static double metadataLimit(float expected,int field){
  if(!Float.isFinite(expected))return Double.NaN;
  if(field<6||field>=15){
   if(expected<=0)return Double.NaN;
   float stored=(float)(Math.log(expected)/Math.log(2));
   double step=.5/(Math.abs(stored)>1?4096.0:268435456.0)+2.0*Math.ulp(stored);
   return expected*Math.expm1(step*Math.log(2))+4.0*Math.ulp(expected);
  }
  if(field<9){
   if(expected<=0)return Double.NaN;
   float stored=1f/expected;
   double step=.5/(Math.abs(stored)>1?4096.0:268435456.0)+2.0*Math.ulp(stored);
   double inverse=1.0/expected;
   if(step>=inverse)return Double.NaN;
   return 1.0/(inverse-step)-expected+4.0*Math.ulp(expected);
  }
  return .5/(Math.abs(expected)>1?4096.0:268435456.0)+2.0*Math.ulp(expected);
 }
 static boolean metadataWithin(float[] actual,float[] expected){
  if(actual.length!=expected.length)return false;
  for(int i=0;i<actual.length;i++){
   double limit=metadataLimit(expected[i],i);
   if(!Float.isFinite(actual[i])||!Double.isFinite(limit)||Math.abs((double)actual[i]-expected[i])>limit)return false;
  }
  return true;
 }
 static boolean metadataNear(Gainmap g,float[] expected){return metadataWithin(metadata(g),expected);}

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
 // RGBA gainmaps can use premultiplied storage. Gain uses the stored RGB bytes,
 // not getPixel()'s unpremultiplied colors; ALPHA_8 uses its single stored byte.
 static final class RawMap {
  final byte[] data;final int width,height,stride,channels;
  RawMap(Bitmap map){
   width=map.getWidth();height=map.getHeight();stride=map.getRowBytes();
   if(map.getConfig()==Bitmap.Config.ALPHA_8)channels=1;
   else if(map.getConfig()==Bitmap.Config.ARGB_8888)channels=4;
   else throw new IllegalArgumentException("Unsupported gainmap pixels in test: "+map.getConfig());
   data=pixels(map);
  }
  int channel(int x,int y,int channel){return data[y*stride+x*channels+(channels==1?0:channel)]&255;}
  int signal(int x,int y){return Math.max(channel(x,y,0),Math.max(channel(x,y,1),channel(x,y,2)));}
 }
 static boolean nonzero(Bitmap map){RawMap m=new RawMap(map);for(int y=0;y<m.height;y++)for(int x=0;x<m.width;x++)if(m.signal(x,y)>0)return true;return false;}
 // Android's fully-applied SDR-to-HDR equation, using stored gain pixels.
 static double sdr(Bitmap b,int x,int y,int channel){return ((ColorSpace.Rgb)b.getColorSpace()).getEotf().applyAsDouble(b.getColor(x,y).getComponents()[channel]);}
 static double hdr(Bitmap b,int x,int y,int channel){return hdr(b,new RawMap(b.getGainmap().getGainmapContents()),x,y,channel);}
 static double hdr(Bitmap b,RawMap map,int x,int y,int channel){
  Gainmap g=b.getGainmap();int q=map.channel(x*map.width/b.getWidth(),y*map.height/b.getHeight(),channel);
  double factor=Math.exp(Math.log(g.getRatioMin()[channel])+(Math.log(g.getRatioMax()[channel])-Math.log(g.getRatioMin()[channel]))*Math.pow(q/255.0,g.getGamma()[channel]));
  return Math.max(0,(sdr(b,x,y,channel)+g.getEpsilonSdr()[channel])*factor-g.getEpsilonHdr()[channel]);
 }
 static double peakHdr(Bitmap b){RawMap map=new RawMap(b.getGainmap().getGainmapContents());double peak=0;for(int y=0;y<b.getHeight();y++)for(int x=0;x<b.getWidth();x++)for(int c=0;c<3;c++)peak=Math.max(peak,hdr(b,map,x,y,c));return peak;}
 // Read the SOF sampling bytes in both complete JPEG images, skipping metadata
 // segments and entropy-coded data instead of searching arbitrary FF D8 bytes.
 static boolean bothPlanes444(byte[] jpeg){
  int p=0,planes=0;
  try{
   while(p<jpeg.length){
    if((jpeg[p++]&255)!=255||(jpeg[p++]&255)!=216)return false;boolean frame=false;
    while(true){
     if((jpeg[p++]&255)!=255)return false;while((jpeg[p]&255)==255)p++;int marker=jpeg[p++]&255;
     if(marker==217)break;
     int size=(jpeg[p]&255)*256+(jpeg[p+1]&255),end=p+size;if(size<2||end>jpeg.length)return false;
     if(marker==192||marker==193||marker==194){
      int components=jpeg[p+7]&255;if(components!=1&&components!=3)return false;
      for(int i=0;i<components;i++)if((jpeg[p+9+3*i]&255)!=0x11)return false;
      frame=true;
     }
     p=end;
     if(marker==218){
      while(p<jpeg.length){
       if((jpeg[p]&255)!=255){p++;continue;}int next=p+1;while((jpeg[next]&255)==255)next++;
       int code=jpeg[next]&255;if(code==0||(code>=208&&code<=215)){p=next+1;continue;}break;
      }
     }
    }
    if(!frame)return false;planes++;
   }
   return planes==2;
  }catch(IndexOutOfBoundsException malformed){return false;}
 }
 static void recycleHdr(Bitmap b){if(b==null||b.isRecycled())return;Bitmap m=b.hasGainmap()?b.getGainmap().getGainmapContents():null;b.recycle();if(m!=null&&!m.isRecycled())m.recycle();}
 static void printMetadataComparison(String label,Original expected,Bitmap actual,boolean comparePixels){
  System.out.println("METADATA_DIAGNOSTIC path="+label+" decoded="+(actual!=null)+" hasGainmap="+(actual!=null&&actual.hasGainmap())+" sourceUnchanged="+expected.unchanged());
  if(actual==null)return;
  System.out.println("COLORSPACE expected="+expected.space+" actual="+actual.getColorSpace()+" equal="+expected.space.equals(actual.getColorSpace())+" size="+actual.getWidth()+"x"+actual.getHeight());
  if(!actual.hasGainmap())return;
  Gainmap gain=actual.getGainmap();float[] values=metadata(gain);
  String[] names={"ratioMin.R","ratioMin.G","ratioMin.B","ratioMax.R","ratioMax.G","ratioMax.B","gamma.R","gamma.G","gamma.B","epsilonSdr.R","epsilonSdr.G","epsilonSdr.B","epsilonHdr.R","epsilonHdr.G","epsilonHdr.B","minDisplayRatioForHdrTransition","displayRatioForFullHdr"};
  for(int i=0;i<values.length;i++)System.out.println("GAINMAP_PARAMETER "+names[i]+" expected="+expected.params[i]+" actual="+values[i]+" delta="+((double)values[i]-expected.params[i])+" allowedError="+metadataLimit(expected.params[i],i)+" expectedBits=0x"+Integer.toHexString(Float.floatToRawIntBits(expected.params[i]))+" actualBits=0x"+Integer.toHexString(Float.floatToRawIntBits(values[i])));
  System.out.println("GAINMAP_PIXELS expectedConfig="+expected.map.getConfig()+" actualConfig="+gain.getGainmapContents().getConfig()+" actualNonzero="+nonzero(gain.getGainmapContents()));
  if(Build.VERSION.SDK_INT>=36)System.out.println("GAINMAP_DIRECTION expected="+expected.direction+" actual="+gain.getGainmapDirection()+" alternativeExpected="+expected.alternative+" alternativeActual="+gain.getAlternativeImagePrimaries());
  if(comparePixels&&actual.getWidth()==expected.source.getWidth()&&actual.getHeight()==expected.source.getHeight()){
   for(int channel=0;channel<3;channel++)System.out.println("RADIANCE channel="+channel+" expectedSdr="+sdr(expected.source,16,12,channel)+" actualSdr="+sdr(actual,16,12,channel)+" expectedHdr="+hdr(expected.source,16,12,channel)+" actualHdr="+hdr(actual,16,12,channel));
  }
 }
 static void diagnoseRoundtrip(Original original,Bitmap decoded,String label){
  System.out.println("ROUNDTRIP_DIAGNOSTIC "+label+" metadataTolerance=Skia_ISO_rational_quantization_per_field");
  printMetadataComparison("repacked-jpeg444",original,decoded,true);
  Bitmap platform=null,template=null;
  try{
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();boolean encoded=original.source.compress(Bitmap.CompressFormat.JPEG,100,bytes);
   System.out.println("ANDROID_JPEG encoded="+encoded+" bytes="+bytes.size());
   if(encoded){byte[] jpeg=bytes.toByteArray();platform=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);printMetadataComparison("android-direct-jpeg",original,platform,true);}
  }catch(Throwable failure){System.out.println("ANDROID_JPEG_DIAGNOSTIC_FAILED "+failure);}
  finally{recycleHdr(platform);}
  try{
   byte[] jpeg=HdrJpeg444.metadataTemplate(original.source);template=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);
   // The 1x1 template uses white placeholders, so only compare metadata here.
   printMetadataComparison("android-metadata-template",original,template,false);
  }catch(Throwable failure){System.out.println("TEMPLATE_DIAGNOSTIC_FAILED "+failure);}
  finally{recycleHdr(template);}
 }
 static Bitmap decode(byte[] jpeg)throws IOException{Bitmap b=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);if(b==null||!b.hasGainmap())throw new IOException("JPEG lost its HDR gainmap");return b;}
 static void verifyMetadata(Original original,Bitmap decoded,String label)throws Exception{
  boolean valid=original.space.equals(decoded.getColorSpace())&&metadataNear(decoded.getGainmap(),original.params);
  printMetadataComparison("repacked-jpeg444 "+label,original,decoded,true);
  if(!valid)diagnoseRoundtrip(original,decoded,label);
  check(valid,"JPEG retains base primaries and metadata within ISO encoding precision "+label);
  Bitmap platform=null;
  try{
   ByteArrayOutputStream nativeBytes=new ByteArrayOutputStream();
   if(!original.source.compress(Bitmap.CompressFormat.JPEG,100,nativeBytes))throw new IOException("Android reference JPEG encode failed");
   platform=decode(nativeBytes.toByteArray());
   boolean same=Arrays.equals(metadata(platform.getGainmap()),metadata(decoded.getGainmap()));
   if(!same)printMetadataComparison("android-direct-jpeg "+label,original,platform,true);
   check(same,"JPEG 4:4:4 adds no metadata error beyond Android serialization "+label);
  }finally{recycleHdr(platform);}
  float[] lost=original.params.clone(),merged=original.params.clone(),resetGamma=original.params.clone(),resetOffset=original.params.clone();
  lost[3]=1;merged[4]=merged[5]=merged[3];resetGamma[6]=1;resetOffset[10]=resetOffset[9];
  check(!metadataWithin(lost,original.params)&&!metadataWithin(merged,original.params)&&!metadataWithin(resetGamma,original.params)&&!metadataWithin(resetOffset,original.params),"ISO allowance rejects lost gain, merged channels, reset gamma and changed offsets "+label);
 }
 static boolean radianceNear(Bitmap expected,Bitmap actual,int x,int y){
  for(int c=0;c<3;c++)if(Math.abs(hdr(actual,x,y,c)-hdr(expected,x,y,c))>Math.max(.006,hdr(expected,x,y,c)*.05))return false;return true;
 }
 static boolean baseNear(Bitmap expected,Bitmap actual,int x,int y){for(int c=0;c<3;c++)if(Math.abs(sdr(expected,x,y,c)-sdr(actual,x,y,c))>.01)return false;return true;}
 static Bitmap reencode(Bitmap input)throws Exception{
  Original original=new Original(input);byte[] jpeg=HdrJpeg444.encode(input);
  if(!original.unchanged())throw new AssertionError("JPEG encoder mutated its input base, map or metadata");return decode(jpeg);
 }
 static void darkPreservation(ColorSpace.Named space,boolean alpha)throws Exception{
  String label=space+" mapAlpha="+alpha;Bitmap in=field(space,alpha,false,false),decoded=null;Original original=new Original(in);
  try{
   check(nonzero(original.map)&&peakHdr(in)<.8,"dark fixture has real gain below SDR white "+label);
   check(hdr(in,16,12,0)-sdr(in,16,12,0)>.02,"dark fixture contains an actual HDR/SDR difference "+label);
   byte[] jpeg=HdrJpeg444.encode(in);decoded=decode(jpeg);
   check(original.unchanged(),"encoding retains the exact original dark base, map and metadata "+label);
   check(bothPlanes444(jpeg),"dark base and gainmap JPEG planes use 1x1 sampling "+label);
   check(decoded.getWidth()==32&&decoded.getHeight()==24&&decoded.getGainmap().getGainmapContents().getWidth()==32&&decoded.getGainmap().getGainmapContents().getHeight()==24,"dark JPEG retains both plane dimensions "+label);
   verifyMetadata(original,decoded,label);
   check(nonzero(decoded.getGainmap().getGainmapContents())&&HdrSignal.inspect(decoded).hdr,"dark JPEG remains a nonempty HDR image "+label);
   check(baseNear(in,decoded,16,12)&&radianceNear(in,decoded,16,12)&&hdr(decoded,16,12,0)-sdr(decoded,16,12,0)>.02,"dark JPEG preserves SDR/HDR separation without absorbing gain into the base "+label);
   boolean stable=true;
   for(int i=0;i<3;i++){
    Bitmap next=reencode(decoded);recycleHdr(decoded);decoded=next;
    stable&=metadataNear(decoded.getGainmap(),original.params)&&baseNear(in,decoded,16,12)&&radianceNear(in,decoded,16,12)&&hdr(decoded,16,12,0)-sdr(decoded,16,12,0)>.02;
   }
   check(stable,"repeated dark JPEG encoding retains the original base and gain separation "+label);
   check(original.unchanged(),"all dark JPEG operations leave the source exactly intact "+label);
  }finally{recycleHdr(decoded);recycleHdr(in);}
 }
 static boolean person(int x,int y){return x>=8&&x<24&&y>=8&&y<24;}
 static boolean sky(int x,int y){return x>=32&&x<48&&y<16;}
 static Bitmap mixed(ColorSpace.Named named,boolean alpha){
  Bitmap b=Bitmap.createBitmap(48,32,Bitmap.Config.ARGB_8888,false,ColorSpace.get(named));b.eraseColor(Color.rgb(160,160,160));
  Bitmap m=Bitmap.createBitmap(48,32,alpha?Bitmap.Config.ALPHA_8:Bitmap.Config.ARGB_8888);m.eraseColor(alpha?Color.TRANSPARENT:Color.BLACK);
  for(int y=0;y<32;y++)for(int x=0;x<48;x++){
   if(person(x,y)){b.setPixel(x,y,Color.rgb(72,72,72));m.setPixel(x,y,alpha?Color.argb(192,0,0,0):Color.rgb(192,160,224));}
   if(sky(x,y)){b.setPixel(x,y,Color.rgb(220,220,220));m.setPixel(x,y,alpha?Color.argb(255,0,0,0):Color.WHITE);}
  }
  Gainmap g=new Gainmap(m);g.setRatioMin(1,1,1);g.setRatioMax(2.5f,3,3.5f);g.setGamma(.8f,1.2f,.9f);
  g.setEpsilonSdr(1f/64,1f/80,1f/96);g.setEpsilonHdr(1f/64,1f/80,1f/96);g.setMinDisplayRatioForHdrTransition(1.125f);g.setDisplayRatioForFullHdr(4);
  if(Build.VERSION.SDK_INT>=36)g.setAlternativeImagePrimaries(b.getColorSpace());b.setGainmap(g);return b;
 }
 static boolean mixedLocations(Bitmap b){
  RawMap map=new RawMap(b.getGainmap().getGainmapContents());if(map.width!=48||map.height!=32)return false;
  for(int y=0;y<32;y++)for(int x=0;x<48;x++)if((map.signal(x,y)>8)!=(person(x,y)||sky(x,y)))return false;return true;
 }
 static void mixedPreservation(ColorSpace.Named space,boolean alpha)throws Exception{
  String label=space+" mapAlpha="+alpha;Bitmap in=mixed(space,alpha),decoded=null;Original original=new Original(in);
  try{
   check(hdr(in,16,16,0)<1&&hdr(in,16,16,0)-sdr(in,16,16,0)>.02&&hdr(in,40,8,0)>1&&Math.abs(hdr(in,4,28,0)-sdr(in,4,28,0))<.000001,"mixed fixture contains dark gained subject, HDR sky and neutral area "+label);
   byte[] jpeg=HdrJpeg444.encode(in);decoded=decode(jpeg);
   check(original.unchanged(),"mixed scene encoding keeps every original base/gain byte and metadata value "+label);
   check(bothPlanes444(jpeg)&&decoded.getWidth()==48&&decoded.getHeight()==32,"mixed JPEG retains dimensions and 4:4:4 sampling "+label);
   verifyMetadata(original,decoded,"mixed "+label);
   check(mixedLocations(decoded),"mixed JPEG gain stays at the subject and sky, with only <=8-code neutral-area quantization "+label);
   check(baseNear(in,decoded,16,16)&&radianceNear(in,decoded,16,16)&&radianceNear(in,decoded,40,8)&&Math.abs(hdr(decoded,4,28,0)-sdr(decoded,4,28,0))<.006,"mixed subject and sky keep separate SDR/HDR responses without boosting the neutral area "+label);
   boolean stable=true;for(int i=0;i<3;i++){
    Bitmap next=reencode(decoded);recycleHdr(decoded);decoded=next;
    stable&=mixedLocations(decoded)&&metadataNear(decoded.getGainmap(),original.params)&&baseNear(in,decoded,16,16)&&radianceNear(in,decoded,16,16)&&radianceNear(in,decoded,40,8);
   }
   check(stable&&original.unchanged(),"repeated mixed JPEG encoding neither relocates gain nor absorbs subject gain into the base "+label);
  }finally{recycleHdr(decoded);recycleHdr(in);}
 }
 static void singlePixel(ColorSpace.Named space,boolean alpha)throws Exception{
  String label=space+" mapAlpha="+alpha;Bitmap in=field(space,alpha,false,true),decoded=null;
  in.eraseColor(Color.rgb(220,220,220));in.getGainmap().getGainmapContents().setPixel(17,18,alpha?Color.argb(255,0,0,0):Color.WHITE);Original original=new Original(in);
  try{
   byte[] jpeg=HdrJpeg444.encode(in);decoded=decode(jpeg);RawMap map=new RawMap(decoded.getGainmap().getGainmapContents());
   int center=map.signal(17,18),outside=0;
   for(int y=0;y<map.height;y++)for(int x=0;x<map.width;x++)if(x!=17||y!=18)outside=Math.max(outside,map.signal(x,y));
   System.out.println("SINGLE_PIXEL "+label+" center="+center+" expected=255 maxOther="+outside);
   check(original.unchanged()&&bothPlanes444(jpeg),"single-pixel source is unchanged and both JPEG planes retain 4:4:4 "+label);
   check(Math.abs(255-center)<=4&&outside<=4,"one maximum-gain pixel stays at its original coordinate with <=4-code JPEG error "+label);
   check(hdr(decoded,17,18,0)>1&&HdrSignal.inspect(decoded).hdr,"single-pixel HDR survives without an area threshold "+label);
  }finally{recycleHdr(decoded);recycleHdr(in);}
 }
 static void emptyControl(ColorSpace.Named space,boolean alpha)throws Exception{
  Bitmap in=field(space,alpha,false,true),decoded=null;Original original=new Original(in);String label=space+" mapAlpha="+alpha;
  try{
   decoded=decode(HdrJpeg444.encode(in));
   check(original.unchanged()&&!nonzero(decoded.getGainmap().getGainmapContents()),"truly black gainmap remains black after JPEG encoding "+label);
   check(!HdrSignal.inspect(decoded).hdr&&Math.abs(hdr(decoded,16,12,0)-sdr(decoded,16,12,0))<.000001,"an empty gainmap does not acquire invented HDR brightness "+label);
  }finally{recycleHdr(decoded);recycleHdr(in);}
 }
 static void localConversion(String input,String output)throws Exception{
  BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;
  Bitmap in=BitmapFactory.decodeFile(input,options),decoded=null;
  try{
   if(in==null||!in.hasGainmap())throw new IOException("Local input must contain a real gainmap");Original original=new Original(in);
   byte[] jpeg=HdrJpeg444.encode(in);decoded=decode(jpeg);
   check(original.unchanged(),"local input base, stored gain pixels and metadata are exactly unchanged");
   check(bothPlanes444(jpeg)&&metadataNear(decoded.getGainmap(),original.params),"local JPEG is 4:4:4 with original metadata within ISO quantization");
   RawMap before=new RawMap(original.map),after=new RawMap(decoded.getGainmap().getGainmapContents());
   check(before.width==after.width&&before.height==after.height&&in.getWidth()==decoded.getWidth()&&in.getHeight()==decoded.getHeight(),"local JPEG preserves both plane dimensions");
   long differences=0,over4=0;int maximum=0,beforePeak=0,afterPeak=0;
   for(int y=0;y<before.height;y++)for(int x=0;x<before.width;x++)for(int c=0;c<3;c++){
    int a=before.channel(x,y,c),b=after.channel(x,y,c),error=Math.abs(a-b);differences+=error;if(error>4)over4++;maximum=Math.max(maximum,error);beforePeak=Math.max(beforePeak,a);afterPeak=Math.max(afterPeak,b);
   }
   System.out.println("LOCAL_GAINMAP_ERROR storedBytes=true max="+maximum+" mean="+(differences/(3.0*before.width*before.height))+" channelsOver4="+over4+" sourcePeak="+beforePeak+" jpegPeak="+afterPeak);
   java.nio.file.Path file=java.nio.file.Paths.get(output);if(file.getParent()!=null)java.nio.file.Files.createDirectories(file.getParent());java.nio.file.Files.write(file,jpeg);
   System.out.println("LOCAL_JPEG_WRITTEN bytes="+jpeg.length+" path="+file);
  }finally{recycleHdr(decoded);recycleHdr(in);}
 }
 public static void main(String[] a){try{run(a);System.exit(0);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
 static void run(String[] a)throws Exception{
  if(a.length!=1&&a.length!=3)throw new IllegalArgumentException("Expected native SO, optionally input HDR image and output JPEG");
  System.load(a[0]);NativeBridge.loaded=true;
  if(a.length==3){localConversion(a[1],a[2]);return;}
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
  boolean rejected=false;try{HdrJpeg444.encode(transparent);}catch(IOException e){rejected=true;}check(rejected&&transparent.hasGainmap(),"transparent JPEG input rejected without input mutation");transparent.recycle();
  Bitmap low=source(false),small=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);small.eraseColor(Color.WHITE);low.setGainmap(new Gainmap(low.getGainmap(),small));Original lowBefore=new Original(low);Bitmap lowDecode=decode(HdrJpeg444.encode(low));
  check(lowBefore.unchanged()&&lowDecode.getGainmap().getGainmapContents().getWidth()==2&&lowDecode.getGainmap().getGainmapContents().getHeight()==2,"encoding retains native low-resolution gainmap without invented upsampling");recycleHdr(lowDecode);recycleHdr(low);
  Bitmap plain=Bitmap.createBitmap(3,3,Bitmap.Config.ARGB_8888);byte[] plainPixels=pixels(plain);
  boolean sdrRejected=false;try{HdrJpeg444.encode(plain);}catch(IOException expected){sdrRejected=true;}
  check(sdrRejected&&!plain.hasGainmap()&&Arrays.equals(plainPixels,pixels(plain)),"SDR encoding is rejected without creating a fabricated gainmap");plain.recycle();
  for(ColorSpace.Named space:new ColorSpace.Named[]{ColorSpace.Named.DISPLAY_P3,ColorSpace.Named.SRGB})for(boolean alpha:new boolean[]{false,true}){
   darkPreservation(space,alpha);mixedPreservation(space,alpha);singlePixel(space,alpha);emptyControl(space,alpha);
  }
  System.out.println("PRECISION_CODEC_CHECKS="+passed);
 }
}
