package dev.local.miuihdr;

import android.graphics.*;
import java.nio.*;

/** On-device numerical checks using opaque linear FP16 fixtures. */
public final class HdrCaptureImageTest {
    static int checks;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;System.out.println("PASS "+message);}
    static Bitmap source(int[][] values){
        Bitmap b=Bitmap.createBitmap(values.length,1,Bitmap.Config.RGBA_F16,false,ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB));
        ByteBuffer data=ByteBuffer.allocate(b.getRowBytes()).order(ByteOrder.nativeOrder());
        for(int[] pixel:values){for(int value:pixel)data.putShort((short)value);data.putShort((short)0x3c00);}
        data.rewind();b.copyPixelsFromBuffer(data);return b;
    }
    static byte[] pixels(Bitmap bitmap){ByteBuffer data=ByteBuffer.allocate(bitmap.getByteCount());bitmap.copyPixelsToBuffer(data);return data.array();}
    static double linear(int value){double x=value/255.0;return x<=.04045?x/12.92:Math.pow((x+.055)/1.055,2.4);}
    static void expectFailure(Bitmap b,int dataspace,float ratio,String message){
        boolean failed=false;try{HdrCaptureImage.convert(b,dataspace,ratio,true,true);}catch(IllegalArgumentException|IllegalStateException expected){failed=true;}
        check(failed,message);check(!b.isRecycled(),"rejected source retained");
    }
    public static void main(String[] args)throws Exception{
        System.load(args[0]);NativeBridge.loaded=true;
        int[][] half={{0x4400,0x4000,0x3800},{0x3400,0x4200,0x3c00},{0,0,0},{0x3c00,0x3c00,0x3c00}};
        double[][] wanted={{4,2,.5},{.25,3,1},{0,0,0},{1,1,1}};
        for(boolean rgb:new boolean[]{true,false}){
            Bitmap source=source(half);HdrCaptureImage.Result result=HdrCaptureImage.convert(source,3<<27,4,true,rgb);Bitmap base=result.bitmap();
            check(base.hasGainmap(),"FP16 highlights become gainmap rgb="+rgb);
            check(base.getColorSpace().equals(ColorSpace.get(ColorSpace.Named.SRGB)),"linear input encoded as nonlinear 8-bit base");
            check(result.report()[6]==2,"only two highlight pixels");
            Gainmap gm=base.getGainmap();float[] max=gm.getRatioMax();byte[] b=pixels(base),g=pixels(gm.getGainmapContents());
            for(int p=0;p<4;p++)for(int c=0;c<3;c++){
                double reconstructed=linear(b[4*p+c]&255)*Math.pow(max[c],(g[4*p+c]&255)/255.0);
                check(Math.abs(reconstructed-wanted[p][c])<.035,"HDR RGB reconstruction p="+p+" c="+c+" rgb="+rgb);
            }
            if(rgb)check(max[0]==4&&max[1]==3&&max[2]==1,"independent RGB maxima");
            check(!source.hasGainmap(),"source not mutated");
            byte[] jpg=HdrJpeg444.encode(base);Bitmap decoded=BitmapFactory.decodeByteArray(jpg,0,jpg.length);
            check(decoded!=null&&decoded.hasGainmap(),"RGB HDR JPEG round trip");
            check(decoded.getGainmap().getRatioMax()[0]==max[0],"JPEG retains HDR metadata");
            decoded.recycle();source.recycle();base.recycle();
        }
        Bitmap white=source(new int[][]{{0x3400,0x3400,0x3400}});
        HdrCaptureImage.Result scaled=HdrCaptureImage.convert(white,1<<27,4,true,true);
        check(!scaled.bitmap().hasGainmap(),"normalized SDR white does not invent a highlight");scaled.bitmap().recycle();
        expectFailure(white,2<<27,4,"unknown limited range rejected");
        expectFailure(white,1<<27,Float.NaN,"invalid full-range scale rejected");white.recycle();
        Bitmap bad=source(new int[][]{{0x7c00,0,0}});expectFailure(bad,3<<27,4,"non-finite FP16 rejected");bad.recycle();
        Bitmap neutral=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);Bitmap map=Bitmap.createBitmap(2,2,Bitmap.Config.ALPHA_8);map.eraseColor(Color.WHITE);
        Gainmap gm=new Gainmap(map);gm.setRatioMin(1,1,1);gm.setRatioMax(1,1,1);gm.setEpsilonSdr(0,0,0);gm.setEpsilonHdr(0,0,0);gm.setDisplayRatioForFullHdr(1);neutral.setGainmap(gm);
        check(neutral.hasGainmap()&&!HdrSignal.inspect(neutral).hdr&&"neutral_metadata".equals(HdrSignal.inspect(neutral).state),"Leica-style neutral standard metadata distinguished from missing map");
        for(String mode:new String[]{CaptureRatio.AUTO,CaptureRatio.OFFSET,CaptureRatio.FORCE}){
            check(!CaptureRatio.apply(neutral,1,4,mode,2,4),"neutral standard metadata not replaced by display/manual headroom mode="+mode);
            check(neutral.getGainmap().getRatioMax()[0]==1&&neutral.getGainmap().getDisplayRatioForFullHdr()==1,"neutral parameters preserved mode="+mode);
        }
        check(GainmapPrecision.tryApply(neutral)==neutral,"neutral private-source map bypasses pixel rebasing");
        check(neutral.getGainmap().getGainmapContents()==map,"neutral map pixels retained");
        gm.setDisplayRatioForFullHdr(4);HdrSignal.invalidate(neutral);
        check(!HdrSignal.inspect(neutral).hdr,"headroom alone cannot turn neutral metadata into HDR");
        gm.setRatioMin(2,2,2);gm.setRatioMax(2,2,2);map.eraseColor(Color.TRANSPARENT);HdrSignal.invalidate(neutral);
        check(HdrSignal.inspect(neutral).hdr,"constant positive gain works with zero map pixels");neutral.recycle();
        if(args.length>1){Bitmap photo=BitmapFactory.decodeFile(args[1]);check(photo!=null&&photo.hasGainmap(),"original Leica photo map decodes");check("neutral_metadata".equals(HdrSignal.inspect(photo).state),"original photo standard metadata diagnosed without modifying it");photo.recycle();}
        System.out.println("HDR_CAPTURE_CHECKS="+checks);
    }
}
