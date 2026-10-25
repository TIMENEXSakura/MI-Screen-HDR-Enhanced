package dev.local.miuihdr;
import android.graphics.*;
import java.util.Arrays;
public final class EditorGainmapTest {
 static int passed;
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);passed++;System.out.println("PASS "+message);}
 static boolean near(float a,float b){return Math.abs(a-b)<0.0001f;}
 static Bitmap image(Gainmap map){Bitmap value=Bitmap.createBitmap(13,11,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.DISPLAY_P3));value.eraseColor(Color.GRAY);value.setGainmap(map);return value;}
 static boolean samePixels(Bitmap a,Bitmap b){if(a.getWidth()!=b.getWidth()||a.getHeight()!=b.getHeight())return false;for(int y=0;y<a.getHeight();y++)for(int x=0;x<a.getWidth();x++)if(a.getPixel(x,y)!=b.getPixel(x,y))return false;return true;}
 // Android Gainmap's documented fully-applied SDR-base formula, sampled at G=0.
 static float blackEndpoint(Gainmap gm,int channel){return (.25f+gm.getEpsilonSdr()[channel])*gm.getRatioMin()[channel]-gm.getEpsilonHdr()[channel];}
 public static void main(String[] args){try{run();}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
 static void run(){
  Bitmap source=Bitmap.createBitmap(13,11,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.DISPLAY_P3));source.eraseColor(Color.GRAY);
  Bitmap map=Bitmap.createBitmap(13,11,Bitmap.Config.ARGB_8888);map.eraseColor(Color.BLACK);map.setPixel(4,6,Color.WHITE);
  Gainmap gm=new Gainmap(map);gm.setRatioMin(1,1,1);gm.setRatioMax(4,4,4);gm.setGamma(1,1,1);gm.setEpsilonSdr(.015625f,.015625f,.015625f);gm.setEpsilonHdr(.015625f,.015625f,.015625f);gm.setMinDisplayRatioForHdrTransition(1);gm.setDisplayRatioForFullHdr(4);source.setGainmap(gm);
  Bitmap full=Bitmap.createBitmap(13,11,Bitmap.Config.ARGB_8888);full.eraseColor(Color.GRAY);
  check(EditorGainmap.restore(source,full,0)&&full.hasGainmap(),"full editor view retains gainmap");
  check(full.getPixel(0,0)==Color.GRAY,"editor restoration leaves base pixels unchanged");
  check(full.getGainmap().getDisplayRatioForFullHdr()==4,"editor retains HDR headroom");
  Bitmap crop=Bitmap.createBitmap(13,5,Bitmap.Config.ARGB_8888);
  check(EditorGainmap.restore(source,crop,3),"long-crop gainmap restored");
  Bitmap cm=crop.getGainmap().getGainmapContents();
  check(cm.getWidth()==13&&cm.getHeight()==5,"gainmap uses identical crop geometry");
  boolean aligned=true;for(int y=0;y<5;y++)for(int x=0;x<13;x++)aligned&=cm.getPixel(x,y)==map.getPixel(x,y+3);
  check(aligned,"single bright pixel remains at its exact cropped location");
  check(!EditorGainmap.restore(source,crop,-1)&&!EditorGainmap.restore(source,crop,9),"invalid crop rejected");
  Bitmap mismatched=Bitmap.createBitmap(3,5,Bitmap.Config.ARGB_8888);check(!EditorGainmap.restore(source,mismatched,0),"unsupported scale rejected");
  Bitmap plain=source.copy(Bitmap.Config.ARGB_8888,false);plain.setGainmap(null);
  check(!EditorGainmap.restore(plain,full,0)&&!full.hasGainmap(),"SDR edit clears stale gainmap");
  check(CaptureRatio.choose(4,8,0)==4,"valid native brightness metadata preserved");
  check(CaptureRatio.choose(1,6,0)==6,"missing native ratio uses current display");
  check(CaptureRatio.choose(Float.NaN,Float.NaN,0)==1,"unavailable ratios do not invent HDR");
  check(CaptureRatio.choose(4,8,2)==2,"manual ratio overrides metadata");
  check(!CaptureRatio.apply(plain,1,4,0)&&!plain.hasGainmap(),"ratio cannot create a missing gainmap");
  check(CaptureRatio.apply(source,4,8,2)&&source.getGainmap().getDisplayRatioForFullHdr()==2,"manual ratio changes brightness metadata");
  Bitmap unchanged=source.getGainmap().getGainmapContents();boolean same=true;
  for(int y=0;y<11;y++)for(int x=0;x<13;x++)same&=unchanged.getPixel(x,y)==map.getPixel(x,y);
  check(same,"ratio adjustment preserves every gainmap pixel");
  check(gm.getDisplayRatioForFullHdr()==4,"shared original metadata not mutated");
  check(CaptureRatio.choose(4,8,CaptureRatio.OFFSET,2,99)==6,"positive offset uses native ratio before display ratio");
  check(CaptureRatio.choose(4,8,CaptureRatio.OFFSET,-1,99)==3,"negative offset subtracts from original ratio");
  check(CaptureRatio.choose(1,6,CaptureRatio.OFFSET,-1,99)==5,"offset uses display ratio when native metadata is absent");
  check(CaptureRatio.choose(4,8,CaptureRatio.OFFSET,-99,99)==1,"negative offset clamps to neutral 1x");
  check(CaptureRatio.choose(4,8,CaptureRatio.OFFSET,Float.MAX_VALUE,99)==10000,"large finite offset clamps without overflow");
  check(Float.isNaN(CaptureRatio.choose(Float.NaN,1,CaptureRatio.OFFSET,2,4)),"offset cannot invent an unknown starting ratio");
  check(Float.isNaN(CaptureRatio.choose(4,8,CaptureRatio.OFFSET,Float.NaN,4))&&Float.isNaN(CaptureRatio.choose(4,8,CaptureRatio.OFFSET,Float.POSITIVE_INFINITY,4)),"non-finite offsets rejected");
  check(Float.isNaN(CaptureRatio.choose(4,8,CaptureRatio.FORCE,0,.5f))&&Float.isNaN(CaptureRatio.choose(4,8,CaptureRatio.FORCE,0,10001))&&Float.isNaN(CaptureRatio.choose(4,8,CaptureRatio.FORCE,0,Float.NaN)),"invalid forced ratios rejected");
  check(CaptureRatio.choose(Float.NaN,Float.NaN,CaptureRatio.FORCE,0,1)==1,"forced ratio explicitly supports 1x");
  check(CaptureRatio.apply(source,2,8,CaptureRatio.OFFSET,2,4)&&source.getGainmap().getDisplayRatioForFullHdr()==6,"offset starts from saved original 4x after prior forced 2x");
  Gainmap plus=source.getGainmap();
  check(!CaptureRatio.apply(source,6,8,CaptureRatio.OFFSET,2,4)&&source.getGainmap()==plus,"repeated conversion does not accumulate the offset");
  check(near(blackEndpoint(plus,0),blackEndpoint(gm,0)),"increasing the peak does not brighten black gainmap pixels");
  check(Arrays.equals(plus.getGamma(),gm.getGamma())&&Arrays.equals(plus.getEpsilonSdr(),gm.getEpsilonSdr())&&Arrays.equals(plus.getEpsilonHdr(),gm.getEpsilonHdr()),"ordinary adjustment preserves gamma and epsilon metadata");
  check(CaptureRatio.apply(source,6,8,CaptureRatio.OFFSET,-1,4)&&source.getGainmap().getDisplayRatioForFullHdr()==3,"changing to a negative offset still uses original 4x");
  check(CaptureRatio.apply(source,3,8,CaptureRatio.AUTO,0,4)&&source.getGainmap().getDisplayRatioForFullHdr()==4&&Arrays.equals(source.getGainmap().getRatioMax(),gm.getRatioMax()),"returning to auto restores original native metadata");
  check(!CaptureRatio.apply(source,4,8,CaptureRatio.AUTO,0,4),"repeated automatic conversion leaves valid native metadata untouched");
  check(CaptureRatio.apply(source,4,8,CaptureRatio.FORCE,0,1)&&source.hasGainmap()&&source.getGainmap().getDisplayRatioForFullHdr()==1,"forced 1x retains a neutral gainmap");
  check(Arrays.equals(source.getGainmap().getRatioMin(),new float[]{1,1,1})&&Arrays.equals(source.getGainmap().getRatioMax(),new float[]{1,1,1})&&near(blackEndpoint(source.getGainmap(),0),.25f),"1x has neither HDR boost nor residual epsilon offset");
  check(CaptureRatio.apply(source,1,8,CaptureRatio.OFFSET,2,4)&&source.getGainmap().getDisplayRatioForFullHdr()==6,"relative mode recovers original headroom after forced 1x");
  check(CaptureRatio.apply(source,6,8,CaptureRatio.OFFSET,0,4)&&source.getGainmap().getDisplayRatioForFullHdr()==4,"zero offset preserves original native metadata");
  check(samePixels(source.getGainmap().getGainmapContents(),map)&&source.getPixel(4,6)==Color.GRAY&&source.getColorSpace().equals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3)),"all adjustments preserve gainmap pixels, SDR pixels and color space");
  Bitmap unknown=image(gm);Gainmap before=unknown.getGainmap();
  check(!CaptureRatio.apply(unknown,Float.NaN,1,CaptureRatio.OFFSET,2,4)&&unknown.getGainmap()==before,"unknown relative baseline leaves attached gainmap untouched");
  check(CaptureRatio.apply(unknown,1,6,CaptureRatio.OFFSET,-1,4)&&unknown.getGainmap().getDisplayRatioForFullHdr()==5,"display fallback is applied to a real gainmap");
  check(!CaptureRatio.apply(plain,4,8,CaptureRatio.FORCE,0,8)&&!plain.hasGainmap(),"forced ratio also cannot create a missing gainmap");
  Gainmap colored=new Gainmap(map);colored.setRatioMin(2,1,.5f);colored.setRatioMax(4,8,2);colored.setGamma(2,1,.5f);colored.setEpsilonSdr(.04f,.05f,.06f);colored.setEpsilonHdr(.01f,.02f,.03f);colored.setMinDisplayRatioForHdrTransition(3);colored.setDisplayRatioForFullHdr(8);
  Bitmap channels=image(colored);
  check(CaptureRatio.apply(channels,8,8,CaptureRatio.FORCE,0,2),"forced reduction handles channel-specific metadata");
  Gainmap reduced=channels.getGainmap();float[] lo=reduced.getRatioMin(),hi=reduced.getRatioMax();
  check(lo[0]<=hi[0]&&lo[1]<=hi[1]&&lo[2]<=hi[2]&&reduced.getMinDisplayRatioForHdrTransition()>=1&&reduced.getMinDisplayRatioForHdrTransition()<2,"reduced peaks keep valid gain and display transition bounds");
  check(near(hi[0],(float)Math.cbrt(4))&&near(hi[1],2)&&near(hi[2],(float)Math.cbrt(2)),"relative channel strengths are preserved in log space");
  check(Arrays.equals(reduced.getGamma(),colored.getGamma())&&Arrays.equals(reduced.getEpsilonSdr(),colored.getEpsilonSdr())&&Arrays.equals(reduced.getEpsilonHdr(),colored.getEpsilonHdr()),"channel-specific gamma and epsilons survive ordinary scaling");
  check(CaptureRatio.apply(channels,2,8,CaptureRatio.FORCE,0,1)&&Arrays.equals(channels.getGainmap().getEpsilonHdr(),channels.getGainmap().getEpsilonSdr()),"1x neutralizes unequal epsilon endpoints");
  check(CaptureRatio.apply(channels,1,8,CaptureRatio.AUTO,0,4)&&Arrays.equals(channels.getGainmap().getRatioMin(),colored.getRatioMin())&&Arrays.equals(channels.getGainmap().getEpsilonHdr(),colored.getEpsilonHdr())&&channels.getGainmap().getMinDisplayRatioForHdrTransition()==3,"automatic restoration recovers channel minima, epsilon and transition metadata");
  check(colored.getDisplayRatioForFullHdr()==8&&colored.getRatioMin()[0]==2&&colored.getEpsilonHdr()[0]==.01f&&samePixels(colored.getGainmapContents(),map),"shared source metadata and exact gain pixels remain unmodified");
  Gainmap bad=new Gainmap(map);bad.setRatioMin(3,3,3);bad.setRatioMax(2,2,2);Bitmap malformed=image(bad);Gainmap attachedBad=malformed.getGainmap();
  check(!CaptureRatio.apply(malformed,4,8,CaptureRatio.FORCE,0,4)&&malformed.getGainmap()==attachedBad,"malformed gainmap boundaries are left untouched");
  System.out.println("EDITOR_GAINMAP_CHECKS="+passed);
 }
}
