package dev.local.miuihdr;
import android.graphics.*;
import java.io.*;
import java.nio.*;
import org.json.*;
public class PrecisionProbe {
 static JSONArray array(float[] v) throws Exception {JSONArray a=new JSONArray();for(float f:v)a.put(f);return a;}
 static JSONObject dumpBitmap(Bitmap b,String path) throws Exception {
  boolean copy=b.getConfig()==Bitmap.Config.HARDWARE;if(copy)b=b.copy(Bitmap.Config.ARGB_8888,false);
  JSONObject j=new JSONObject();j.put("width",b.getWidth());j.put("height",b.getHeight());j.put("stride",b.getRowBytes());j.put("config",b.getConfig().toString());j.put("space",String.valueOf(b.getColorSpace()));
  ByteBuffer buf=ByteBuffer.allocate(b.getByteCount());b.copyPixelsToBuffer(buf);try(FileOutputStream f=new FileOutputStream(path)){f.write(buf.array());}if(copy)b.recycle();return j;
 }
 static void dump(Bitmap b,String name) throws Exception {
  if(b==null||!b.hasGainmap())throw new AssertionError("No HDR: "+name);
  Gainmap gm=b.getGainmap();JSONObject j=new JSONObject();j.put("base",dumpBitmap(b,name+".base"));j.put("map",dumpBitmap(gm.getGainmapContents(),name+".map"));
  j.put("min",array(gm.getRatioMin()));j.put("max",array(gm.getRatioMax()));j.put("gamma",array(gm.getGamma()));j.put("epsSdr",array(gm.getEpsilonSdr()));j.put("epsHdr",array(gm.getEpsilonHdr()));j.put("displayMin",gm.getMinDisplayRatioForHdrTransition());j.put("displayMax",gm.getDisplayRatioForFullHdr());
  try(FileWriter f=new FileWriter(name+".json")){f.write(j.toString(2));}
 }
 public static void main(String[] a) throws Exception {
  System.load(a[0]);NativeBridge.loaded=true;Bitmap in=BitmapFactory.decodeFile(a[1]);dump(in,a[2]+"-input");
  long start=System.nanoTime();Bitmap out=GainmapPrecision.apply(in);System.out.println("REBASE_MS="+(System.nanoTime()-start)/1e6);if(out==in)throw new AssertionError("Unprocessed");dump(out,a[2]+"-memory");
  for(String ext:new String[]{"png","jpg"}){
   String path=a[2]+"."+ext;try(FileOutputStream f=new FileOutputStream(path)){if(!out.compress(ext.equals("png")?Bitmap.CompressFormat.PNG:Bitmap.CompressFormat.JPEG,100,f))throw new AssertionError("Encode "+ext);}
   Bitmap decoded=BitmapFactory.decodeFile(path);dump(decoded,a[2]+"-"+ext);decoded.recycle();System.out.println("SAVED "+path);
  }
  Bitmap again=GainmapPrecision.apply(out);dump(again,a[2]+"-repeat");again.recycle();out.recycle();in.recycle();
  if(a.length>3){Bitmap source=BitmapFactory.decodeFile(a[3]);dump(source,a[2]+"-source");source.recycle();}System.out.println("PROBE_COMPLETE");
 }
}
