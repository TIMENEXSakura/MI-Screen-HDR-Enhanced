package dev.local.miuihdr;
import android.graphics.Bitmap;
import android.os.Build;
import java.io.*;
import java.security.*;
final class NativeBridge {
 static boolean loaded,gate;
 static void start(){try{
  System.loadLibrary("miuihdr");loaded=true;
  int result=installGate();gate=result==1;MiuiHook.log("Native SO gate="+gate+" result="+result);
 }catch(Throwable t){MiuiHook.log("Native SO unavailable; using Java fallback: "+t);}}
 static String sha256(String path)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(path)){byte[] b=new byte[32768];int n;while((n=in.read(b))>0)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format("%02x",b&255));return s.toString();}
 static native int captureGainmap(Bitmap source,Bitmap base,Bitmap gain,float normalization,boolean rgb,boolean linear,float[] report);
 static native int rebaseGainmap(Bitmap source,Bitmap gain,Bitmap result,Bitmap output,float[] params,float[] transfer);
 static native byte[] encodeJpeg444(Bitmap bitmap,boolean gainmap);
 static native int installGate();
 static native int firstSignal(Bitmap bitmap);
 static native boolean gainmapChannels(Bitmap bitmap,int[] report);
 static native boolean composeShellGainmap(Bitmap base,Bitmap gain,Bitmap shell,Bitmap result,Bitmap output,float[] params,float[] transfer);
}
