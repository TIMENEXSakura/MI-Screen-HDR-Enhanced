package dev.local.miuihdr;
import android.graphics.*;
import java.io.*;
public class PrecisionDecode {
 public static void main(String[] a)throws Exception {
  for(File f:new File(a[0]).listFiles())if(f.getName().endsWith("444.jpg")){
   Bitmap b=BitmapFactory.decodeFile(f.toString());if(b==null||!b.hasGainmap())throw new AssertionError("HDR decode failed "+f);
   PrecisionProbe.dump(b,f.toString().replace(".jpg",""));System.out.println("HDR_DECODE_OK "+f.getName()+" "+b.getWidth()+"x"+b.getHeight()+" "+b.getColorSpace());b.recycle();
  }
 }
}
