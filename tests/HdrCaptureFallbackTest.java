package dev.local.miuihdr;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** On-device failure injection plus real ordinary FP16/gainmap conversion. */
public final class HdrCaptureFallbackTest {
    private static int checks;
    private static void check(boolean value,String message){
        if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);
    }
    private static final int EXTENDED=3<<27;
    private static Bitmap fixture(){return HdrCaptureImageTest.source(new int[][]{
            {0x4400,0x4000,0x3800},{0x3400,0x4200,0x3c00},{0,0,0},{0x3c00,0x3c00,0x3c00}});}
    private static void verifyOrdinary(HdrCaptureImage.Result result)throws java.io.IOException{
        Bitmap bitmap=result.bitmap();
        check(!result.rgb()&&bitmap.hasGainmap(),"ordinary fallback retains HDR instead of returning an SDR image");
        check(HdrCaptureImage.created(bitmap),"ordinary result remains marked as precise capture");
        int[] channels=new int[3];
        check(NativeBridge.gainmapChannels(bitmap.getGainmap().getGainmapContents(),channels)&&channels[2]==0,
                "ordinary conversion uses common gain with color retained in the base");
        byte[] base=HdrCaptureImageTest.pixels(bitmap),map=HdrCaptureImageTest.pixels(bitmap.getGainmap().getGainmapContents());
        float[] maximum=bitmap.getGainmap().getRatioMax();
        double[][] wanted={{4,2,.5},{.25,3,1},{0,0,0},{1,1,1}};
        for(int p=0;p<4;p++)for(int c=0;c<3;c++){
            double value=HdrCaptureImageTest.linear(base[p*4+c]&255)*Math.pow(maximum[c],(map[p*4+c]&255)/255.0);
            check(Math.abs(value-wanted[p][c])<.035,"ordinary fallback preserves linear HDR pixel="+p+" channel="+c);
        }
        byte[] jpeg=HdrJpeg444.encode(bitmap);Bitmap decoded=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);
        check(decoded!=null&&decoded.hasGainmap(),"ordinary fallback survives the HDR JPEG encoder");
        decoded.recycle();
    }
    public static void main(String[] args)throws Throwable{
        System.load(args[0]);NativeBridge.loaded=true;
        Bitmap source=fixture();byte[] original=HdrCaptureImageTest.pixels(source);
        List<Boolean> calls=new ArrayList<>();List<Throwable> rejected=new ArrayList<>();
        IllegalStateException rgbError=new IllegalStateException("injected RGB conversion failure");
        HdrCaptureImage.Result result=HdrCaptureImage.read(true,true,rgb->{
            calls.add(rgb);if(rgb)throw rgbError;
            return HdrCaptureImage.convert(source,EXTENDED,4,true,false);
        },rejected::add);
        check(calls.equals(List.of(true,false)),"RGB failure retries ordinary precise conversion in order");
        check(rejected.equals(List.of(rgbError)),"RGB failure remains available for diagnostics");
        verifyOrdinary(result);result.bitmap().recycle();
        check(!source.isRecycled()&&!source.hasGainmap()&&Arrays.equals(original,HdrCaptureImageTest.pixels(source)),
                "both attempts leave the same input frame intact");

        calls.clear();rejected.clear();
        result=HdrCaptureImage.read(true,true,rgb->{calls.add(rgb);return HdrCaptureImage.convert(source,EXTENDED,4,true,rgb);},rejected::add);
        check(calls.equals(List.of(true))&&rejected.isEmpty()&&result.rgb(),"next capture tries RGB again; no sticky downgrade");
        result.bitmap().recycle();
        calls.clear();
        result=HdrCaptureImage.read(false,true,rgb->{calls.add(rgb);return HdrCaptureImage.convert(source,EXTENDED,4,true,rgb);},rejected::add);
        check(calls.equals(List.of(false))&&!result.rgb()&&rejected.isEmpty(),"RGB off starts directly with ordinary precise conversion");
        result.bitmap().recycle();

        Bitmap invalid=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);
        calls.clear();rejected.clear();
        result=HdrCaptureImage.read(true,true,rgb->{
            calls.add(rgb);return rgb?new HdrCaptureImage.Result(invalid,new float[10],true):
                    HdrCaptureImage.convert(source,EXTENDED,4,true,false);
        },rejected::add);
        check(calls.equals(List.of(true,false))&&rejected.size()==1,"RGB HDR validation failure also retries ordinary conversion");
        check(invalid.isRecycled(),"rejected RGB result is released before ordinary conversion");
        check(result.bitmap().hasGainmap()&&!result.rgb(),"ordinary HDR result accepted after RGB validation failure");result.bitmap().recycle();

        calls.clear();rejected.clear();
        IllegalStateException normalError=new IllegalStateException("injected ordinary conversion failure");
        try{
            HdrCaptureImage.read(true,true,rgb->{calls.add(rgb);throw rgb?rgbError:normalError;},rejected::add);
            throw new AssertionError("both precise failures must escape to the system fallback");
        }catch(IllegalStateException failure){
            check(failure==normalError&&calls.equals(List.of(true,false)),"system fallback reached only after RGB and ordinary fail");
            check(rejected.equals(List.of(rgbError))&&Arrays.asList(failure.getSuppressed()).contains(rgbError),"both precise failure reasons retained");
        }
        calls.clear();rejected.clear();
        try{
            HdrCaptureImage.read(false,true,rgb->{calls.add(rgb);throw new IllegalStateException("ordinary-only failure");},rejected::add);
            throw new AssertionError("ordinary-only failure must escape");
        }catch(IllegalStateException expected){
            check(calls.equals(List.of(false))&&rejected.isEmpty(),"ordinary-only failure goes to final fallback without trying RGB");
        }

        calls.clear();
        result=HdrCaptureImage.read(true,true,rgb->{calls.add(rgb);if(rgb)throw rgbError;return HdrCaptureImage.convert(source,EXTENDED,4,true,false);},failure->{throw new IllegalStateException("logging failure");});
        check(calls.equals(List.of(true,false))&&result.bitmap().hasGainmap(),"diagnostic failure cannot skip ordinary precise conversion");result.bitmap().recycle();

        Bitmap nonFinite=HdrCaptureImageTest.source(new int[][]{{0x7c00,0,0}});
        calls.clear();rejected.clear();
        try{
            HdrCaptureImage.read(true,true,rgb->{calls.add(rgb);return HdrCaptureImage.convert(nonFinite,EXTENDED,4,true,rgb);},rejected::add);
            throw new AssertionError("invalid source must remain rejected");
        }catch(IllegalStateException failure){
            check(calls.equals(List.of(true,false))&&rejected.size()==1,"invalid source tried by both precise modes before final fallback");
            check(failure.getMessage().contains("-5")&&rejected.get(0).getMessage().contains("-5"),"source validation remains enforced for both modes");
            check(!nonFinite.isRecycled(),"failed attempts preserve original source ownership");
        }
        nonFinite.recycle();
        Bitmap sdr=HdrCaptureImageTest.source(new int[][]{{0x3800,0x3800,0x3800}});
        rejected.clear();result=HdrCaptureImage.read(sdr,EXTENDED,1,false,true,rejected::add);
        check(!result.bitmap().hasGainmap()&&rejected.isEmpty(),"legitimate SDR does not trigger a needless retry");result.bitmap().recycle();
        List<Bitmap> discarded=new ArrayList<>();calls.clear();rejected.clear();
        try{
            HdrCaptureImage.read(true,true,rgb->{calls.add(rgb);HdrCaptureImage.Result value=HdrCaptureImage.convert(sdr,EXTENDED,4,true,rgb);discarded.add(value.bitmap());return value;},rejected::add);
            throw new AssertionError("HDR flag without reconstructable signal must fail both modes");
        }catch(IllegalStateException expected){
            check(calls.equals(List.of(true,false))&&discarded.size()==2,"missing HDR gain is validated separately in both modes");
            check(discarded.stream().allMatch(Bitmap::isRecycled)&&!sdr.isRecycled(),"both rejected outputs released; input retained");
        }
        sdr.recycle();source.recycle();
        System.out.println("HDR_CAPTURE_FALLBACK_CHECKS="+checks);
        System.exit(0);
    }
}
