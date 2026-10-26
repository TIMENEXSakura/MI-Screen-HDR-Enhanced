package dev.local.miuihdr;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Reconstruct from one FP16 capture, in that capture's RGB primaries. */
final class HdrCaptureImage {
    private static final WeakHashMap<Bitmap,Boolean> CREATED=new WeakHashMap<>();
    static boolean created(Bitmap bitmap){synchronized(CREATED){return CREATED.containsKey(bitmap);}}
    record Result(Bitmap bitmap,float[] report,boolean rgb){}
    @FunctionalInterface interface Converter { Result convert(boolean rgb)throws Throwable; }
    static Result read(Bitmap source,int dataspace,float nativeRatio,boolean containsHdr,boolean preferRgb,
                       Consumer<Throwable> rgbRejected)throws Throwable {
        // Both attempts consume the same snapshot and metadata. Only the caller
        // may recapture, after this method has exhausted precise conversion.
        return read(preferRgb,containsHdr,rgb->convert(source,dataspace,nativeRatio,containsHdr,rgb),rgbRejected);
    }
    static Result read(boolean preferRgb,boolean containsHdr,Converter converter,
                       Consumer<Throwable> rgbRejected)throws Throwable {
        Throwable first=null;
        if(preferRgb)try{return checked(converter.convert(true),containsHdr);}
        catch(Throwable failure){
            first=failure;
            // A diagnostic callback must not bypass the normal precise retry.
            try{rgbRejected.accept(failure);}catch(Throwable ignored){}
        }
        try{return checked(converter.convert(false),containsHdr);}
        catch(Throwable failure){
            if(first!=null&&first!=failure)failure.addSuppressed(first);
            throw failure;
        }
    }
    private static Result checked(Result result,boolean containsHdr){
        if(result==null||result.bitmap()==null||result.bitmap().isRecycled())
            throw new IllegalStateException("精准读取未返回有效画面");
        if(containsHdr&&!result.bitmap().hasGainmap()){
            result.bitmap().recycle();
            throw new IllegalStateException("系统标记含 HDR，但精准读取没有可重建的增益");
        }
        return result;
    }
    static Result convert(Bitmap source,int dataspace,float nativeRatio,boolean containsHdr,boolean rgb) {
        if(!NativeBridge.loaded||source==null||source.isRecycled()||source.hasGainmap())throw new IllegalArgumentException("没有可用的独立高精度画面");
        if((long)source.getWidth()*source.getHeight()>16000000)throw new IllegalArgumentException("高精度画面超过处理上限");
        ColorSpace space=source.getColorSpace(),output;boolean linear;
        if(ColorSpace.get(ColorSpace.Named.DISPLAY_P3).equals(space)){output=space;linear=false;}
        else if(ColorSpace.get(ColorSpace.Named.SRGB).equals(space)||ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB).equals(space)){output=ColorSpace.get(ColorSpace.Named.SRGB);linear=false;}
        else if(ColorSpace.get(ColorSpace.Named.LINEAR_SRGB).equals(space)||ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB).equals(space)){output=ColorSpace.get(ColorSpace.Named.SRGB);linear=true;}
        else throw new IllegalArgumentException("尚不支持此高精度输出色彩空间："+space);
        int range=(dataspace>>27)&7;
        // Full-range display-matching captures are normalized to display white.
        // Extended FP16 targets are already expressed relative to SDR white.
        float normalization;
        if(range==1&&Float.isFinite(nativeRatio)&&nativeRatio>=1&&nativeRatio<=10000)normalization=containsHdr?nativeRatio:1;
        else if(range==3)normalization=1;
        else throw new IllegalArgumentException("缺少可信的画面范围或同帧亮度比例");
        Bitmap input=null,base=null,map=null;
        try {
            input=source.getConfig()==Bitmap.Config.RGBA_F16?source:source.copy(Bitmap.Config.RGBA_F16,false);
            if(input==null||input.getConfig()!=Bitmap.Config.RGBA_F16)throw new IllegalStateException("高精度画面读取失败");
            int w=source.getWidth(),h=source.getHeight();
            base=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888,false,output);
            map=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888,false,ColorSpace.get(ColorSpace.Named.SRGB));
            float[] report=new float[10];
            int highlights=NativeBridge.captureGainmap(input,base,map,normalization,rgb,linear,report);
            if(highlights<0)throw new IllegalStateException("高精度画面校验失败，代码 "+highlights);
            if(highlights>0) {
                Gainmap gain=new Gainmap(map);gain.setRatioMin(1,1,1);
                gain.setRatioMax(report[3],report[4],report[5]);gain.setGamma(1,1,1);
                gain.setEpsilonSdr(0,0,0);gain.setEpsilonHdr(0,0,0);
                gain.setMinDisplayRatioForHdrTransition(1);
                float peak=Math.max(report[3],Math.max(report[4],report[5]));
                gain.setDisplayRatioForFullHdr(containsHdr&&Float.isFinite(nativeRatio)&&nativeRatio>1?nativeRatio:peak);
                base.setGainmap(gain);map=null;
            }
            base.setDensity(source.getDensity());
            synchronized(CREATED){CREATED.put(base,true);}
            Result result=new Result(base,report,rgb);base=null;return result;
        }finally{
            if(input!=null&&input!=source)input.recycle();
            if(base!=null)base.recycle();if(map!=null)map.recycle();
        }
    }
}
