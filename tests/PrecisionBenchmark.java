package dev.local.miuihdr;
import android.graphics.*;
import java.util.Arrays;
/** Device-only, no window or screenshot triggers. Compare identical fixture and device. */
public final class PrecisionBenchmark {
 public static void main(String[] args) throws Exception {
  System.load(args[0]); NativeBridge.loaded=true;
  Bitmap input=BitmapFactory.decodeFile(args[1]);
  if(input==null||!input.hasGainmap())throw new AssertionError("HDR fixture required");
  long[] rebase=new long[7],encode=new long[7];int bytes=0;
  for(int i=-2;i<7;i++){
   long start=System.nanoTime();Bitmap precise=GainmapPrecision.apply(input);long middle=System.nanoTime();
   byte[] jpeg=HdrJpeg444.encode(precise);long end=System.nanoTime();bytes=jpeg.length;
   if(i>=0){rebase[i]=middle-start;encode[i]=end-middle;}
   if(precise!=input)precise.recycle();
  }
  Arrays.sort(rebase);Arrays.sort(encode);
  System.out.println("BENCH width="+input.getWidth()+" height="+input.getHeight()+" rebaseMedianMs="+rebase[3]/1e6+" jpegMedianMs="+encode[3]/1e6+" bytes="+bytes);
  input.recycle();System.exit(0);
 }
}
