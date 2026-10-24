package dev.local.miuihdr;

import android.graphics.Bitmap;
import android.graphics.Gainmap;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Reuses Android's HDR metadata; encodes both pixel planes at quality 100, 4:4:4. */
final class HdrJpeg444 {
 private static final int LIMIT=64*1024*1024;
 private static final byte[] MPF={'M','P','F',0};
 private static final Pattern LENGTH=Pattern.compile("Item:Length=\"[0-9]+\"");
 private static void require(boolean b,String why)throws IOException{if(!b)throw new IOException(why);}
 static byte[] encode(Bitmap source)throws IOException{
  require(NativeBridge.loaded&&source!=null&&source.hasGainmap(),"No native HDR encoder");
  require((long)source.getWidth()*source.getHeight()<=16000000,"Image too large");
  Bitmap base=null,map=null;Bitmap raw=source.getGainmap().getGainmapContents();
  try{
   base=source.getConfig()==Bitmap.Config.HARDWARE?source.copy(Bitmap.Config.ARGB_8888,false):source;
   map=raw.getConfig()==Bitmap.Config.HARDWARE?raw.copy(Bitmap.Config.ARGB_8888,false):raw;
   require(base!=null&&map!=null,"Bitmap copy failed");
   byte[] primary=NativeBridge.encodeJpeg444(base,false),gain=NativeBridge.encodeJpeg444(map,true);
   require(primary!=null&&gain!=null,"Unsupported pixels for JPEG 4:4:4");
   return repack(metadataTemplate(source),primary,gain);
  }finally{if(base!=null&&base!=source)base.recycle();if(map!=null&&map!=raw)map.recycle();}
 }
 /** Metadata does not depend on image dimensions. Let Android serialize it
  * from tiny placeholders, instead of encoding both full-size planes twice.
  * Repack replaces SOF/SOS pixels and rewrites MPF offsets and XMP lengths.
  * Gainmap parameters and ICC profile still come from the original image.
  */
 static byte[] metadataTemplate(Bitmap source)throws IOException{
  Bitmap base=null,map=null;
  try{
   base=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888,false,source.getColorSpace());
   base.eraseColor(android.graphics.Color.WHITE);
   Gainmap original=source.getGainmap();
   map=Bitmap.createBitmap(1,1,original.getGainmapContents().getConfig()==Bitmap.Config.ALPHA_8?Bitmap.Config.ALPHA_8:Bitmap.Config.ARGB_8888);
   map.eraseColor(android.graphics.Color.WHITE);
   Gainmap metadata;
   if(Build.VERSION.SDK_INT>=35)metadata=new Gainmap(original,map);
   else{
    metadata=new Gainmap(map);
    float[] p=original.getRatioMin();metadata.setRatioMin(p[0],p[1],p[2]);
    p=original.getRatioMax();metadata.setRatioMax(p[0],p[1],p[2]);
    p=original.getGamma();metadata.setGamma(p[0],p[1],p[2]);
    p=original.getEpsilonSdr();metadata.setEpsilonSdr(p[0],p[1],p[2]);
    p=original.getEpsilonHdr();metadata.setEpsilonHdr(p[0],p[1],p[2]);
    metadata.setMinDisplayRatioForHdrTransition(original.getMinDisplayRatioForHdrTransition());
    metadata.setDisplayRatioForFullHdr(original.getDisplayRatioForFullHdr());
   }
   base.setGainmap(metadata);
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();
   require(base.compress(Bitmap.CompressFormat.JPEG,100,bytes),"Android HDR metadata encode failed");
   return bytes.toByteArray();
  }finally{if(base!=null)base.recycle();if(map!=null)map.recycle();}
 }
 private static final class Part {
  final int marker;final byte[] bytes;
  Part(int marker,byte[] bytes){this.marker=marker;this.bytes=bytes;}
 }
 private static final class Jpeg {
  final ArrayList<Part> header=new ArrayList<>();int scan,end;
 }
 private static int u16(byte[] b,int p)throws IOException{require(p>=0&&p+2<=b.length,"Truncated JPEG");return (b[p]&255)<<8|(b[p+1]&255);}
 private static boolean at(byte[] b,int p,byte[] v){if(p<0||p+v.length>b.length)return false;for(int i=0;i<v.length;i++)if(b[p+i]!=v[i])return false;return true;}
 private static Jpeg parse(byte[] b,int start)throws IOException{
  require(b.length<=LIMIT&&u16(b,start)==0xffd8,"Invalid JPEG start");Jpeg j=new Jpeg();int p=start+2;j.scan=-1;
  while(p<b.length){
   require((b[p]&255)==255,"Invalid JPEG marker");int markStart=p;
   while(p<b.length&&(b[p]&255)==255)p++;
   require(p<b.length,"Truncated marker");int marker=b[p++]&255;
   if(marker==0xd9){require(j.scan>=0,"No JPEG scan");j.end=p;return j;}
   require(marker!=0&&marker!=0xd8&&!(marker>=0xd0&&marker<=0xd7),"Unexpected JPEG marker");
   int size=u16(b,p);require(size>=2&&(long)p+size<=b.length,"Invalid segment length");int end=p+size;
   if(marker==0xda){
    if(j.scan<0)j.scan=markStart;
    p=end;
    // Skip entropy bytes, stuffed FF00 and restart markers; parse the next
    // actual marker normally (also handles multi-scan/progressive templates).
    while(p<b.length){if((b[p]&255)!=255){p++;continue;}int q=p+1;while(q<b.length&&(b[q]&255)==255)q++;require(q<b.length,"Truncated entropy data");int next=b[q]&255;
     if(next==0||(next>=0xd0&&next<=0xd7)){p=q+1;continue;}break;}
   }else{if(j.scan<0)j.header.add(new Part(marker,Arrays.copyOfRange(b,markStart,end)));p=end;}
  }
  throw new IOException("JPEG end missing");
 }
 private static boolean app(Part p){return p.marker>=0xe0&&p.marker<=0xef;}
 private static byte[] segment(int marker,byte[] payload)throws IOException{
  require(payload.length<=65533,"Metadata too large");ByteArrayOutputStream out=new ByteArrayOutputStream(payload.length+4);out.write(255);out.write(marker);out.write((payload.length+2)>>8);out.write((payload.length+2)&255);out.write(payload);return out.toByteArray();
 }
 private static byte[] assemble(Jpeg old,byte[] pixels,Jpeg encoded,int gainSize)throws IOException{
  ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(255);out.write(216);
  for(Part part:old.header)if(app(part)){
   byte[] bytes=part.bytes;
   if(gainSize>=0&&part.marker==0xe1){
    String text=new String(bytes,4,bytes.length-4,StandardCharsets.ISO_8859_1);
    if(text.startsWith("http://ns.adobe.com/xap/1.0/\0")&&text.contains("Item:Length=")){
     Matcher matcher=LENGTH.matcher(text);int n=0;while(matcher.find())n++;
     require(n==1,"Ambiguous gain map XMP length");text=matcher.replaceAll("Item:Length=\""+gainSize+"\"");bytes=segment(part.marker,text.getBytes(StandardCharsets.ISO_8859_1));
    }
   }
   out.write(bytes);
  }
  for(Part part:encoded.header)if(!app(part))out.write(part.bytes);
  out.write(pixels,encoded.scan,encoded.end-encoded.scan);return out.toByteArray();
 }
 private static long read32(byte[] b,int p,boolean little)throws IOException{
  require(p>=0&&p+4<=b.length,"Truncated MPF");long v=0;for(int i=0;i<4;i++)v=v<<8|(b[p+(little?3-i:i)]&255);return v;
 }
 private static int read16(byte[] b,int p,boolean little)throws IOException{return little?((b[p+1]&255)<<8|(b[p]&255)):u16(b,p);}
 private static void put32(byte[] b,int p,long v,boolean little)throws IOException{
  require(p>=0&&p+4<=b.length&&v>=0&&v<=0xffffffffL,"MPF offset overflow");for(int i=0;i<4;i++)b[p+(little?i:3-i)]=(byte)(v>>(i*8));
 }
 static byte[] repack(byte[] template,byte[] primary,byte[] gain)throws IOException{
  Jpeg oldBase=parse(template,0),oldGain=parse(template,oldBase.end);
  require(oldGain.end==template.length,"Unexpected images after gain map");
  Jpeg basePixels=parse(primary,0),gainPixels=parse(gain,0);
  byte[] newGain=assemble(oldGain,gain,gainPixels,-1);
  byte[] newBase=assemble(oldBase,primary,basePixels,newGain.length);
  Jpeg header=parse(newBase,0);int pos=2,mpf=-1,mpfEnd=-1;
  for(Part part:header.header){if(part.marker==0xe2&&at(part.bytes,4,MPF)){require(mpf<0,"Multiple primary MPF blocks");mpf=pos+8;mpfEnd=pos+part.bytes.length;}pos+=part.bytes.length;}
  require(mpf>=0,"Android template has no MPF index");
  boolean little=at(newBase,mpf,new byte[]{'I','I'});require(little||at(newBase,mpf,new byte[]{'M','M'}),"Invalid MPF byte order");
  require(read16(newBase,mpf+2,little)==42,"Invalid MPF TIFF header");
  long ifdLong=(long)mpf+read32(newBase,mpf+4,little);require(ifdLong>=mpf&&ifdLong+2<=mpfEnd,"MPF IFD outside segment");int ifd=(int)ifdLong,n=read16(newBase,ifd,little),entries=-1;boolean twoImages=false;
  require((long)ifd+2+12L*n+4<=mpfEnd,"MPF directory truncated");
  for(int i=0;i<n;i++){
   int p=ifd+2+i*12,tag=read16(newBase,p,little);long count=read32(newBase,p+4,little),value=read32(newBase,p+8,little);
   if(tag==0xb001){require(count==1&&value==2,"Unexpected MPF image count");twoImages=true;}
   if(tag==0xb002){require(entries<0&&count==32,"Unexpected MPF entries");long offset=(long)mpf+value;require(offset>=mpf&&offset+32<=mpfEnd,"MPF entries outside segment");entries=(int)offset;}
  }
  require(twoImages&&entries>=0,"Incomplete MPF index");
  put32(newBase,entries+4,newBase.length,little);put32(newBase,entries+8,0,little);
  put32(newBase,entries+20,newGain.length,little);put32(newBase,entries+24,newBase.length-mpf,little);
  require((long)newBase.length+newGain.length<=LIMIT,"HDR JPEG too large");
  ByteArrayOutputStream out=new ByteArrayOutputStream(newBase.length+newGain.length);out.write(newBase);out.write(newGain);return out.toByteArray();
 }
}
