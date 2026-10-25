package dev.local.miuihdr;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import android.os.Build;

/** Moves recovered SDR pixels into the base; only HDR highlights retain gain.
 * Full-HDR radiance is retained within 8-bit quantization. SDR highlights use
 * a per-pixel chromaticity-preserving clamp, so their SDR appearance changes.
 */
public final class GainmapPrecision {
    static Bitmap tryApply(Bitmap source) {
        try { return apply(source); }
        catch (Throwable t) { android.util.Log.w("MiuiHdrHook", "Pixel precision skipped; original retained", t); return source; }
    }
    public static Bitmap apply(Bitmap source) {
        if (source == null || !source.hasGainmap()) return source;
        if (!NativeBridge.loaded) throw new IllegalStateException("Native precision compositor unavailable");
        Gainmap gm=source.getGainmap(); ColorSpace space=source.getColorSpace();
        if (!(space instanceof ColorSpace.Rgb) ||
                !(space.equals(ColorSpace.get(ColorSpace.Named.DISPLAY_P3)) || space.equals(ColorSpace.get(ColorSpace.Named.SRGB))))
            throw new IllegalArgumentException("Unsupported precision color space");
        if (Build.VERSION.SDK_INT>=36 && gm.getGainmapDirection()!=Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR)
            throw new IllegalArgumentException("HDR base direction");
        ColorSpace alternative=Build.VERSION.SDK_INT>=36?gm.getAlternativeImagePrimaries():null;
        if (alternative!=null && !alternative.equals(space)) throw new IllegalArgumentException("Different gainmap primaries");
        int w=source.getWidth(), h=source.getHeight(); Bitmap raw=gm.getGainmapContents();
        if ((long)w*h>16000000L || raw.getWidth()!=w || raw.getHeight()!=h)
            throw new IllegalArgumentException("Precision requires full-resolution capture gainmap");
        Bitmap base=null, map=null, result=null, out=null;
        try {
            base=source.getConfig()==Bitmap.Config.ARGB_8888?source:source.copy(Bitmap.Config.ARGB_8888,false);
            if (base==null) throw new IllegalStateException("Base copy failed");
            map=raw.getConfig()==Bitmap.Config.HARDWARE?raw.copy(Bitmap.Config.ARGB_8888,false):raw;
            result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888,source.hasAlpha(),space);
            out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            float[] params=new float[21]; float[][] groups={gm.getRatioMin(),gm.getRatioMax(),gm.getGamma(),gm.getEpsilonSdr(),gm.getEpsilonHdr()};
            for(int i=0;i<5;i++) System.arraycopy(groups[i],0,params,i*3,3);
            float[] transfer=new float[256]; java.util.function.DoubleUnaryOperator eotf=((ColorSpace.Rgb)space).getEotf();
            for(int i=0;i<256;i++) transfer[i]=(float)eotf.applyAsDouble(i/255.0);
            int count=NativeBridge.rebaseGainmap(base,map,result,out,params,transfer);
            if(count<0) throw new IllegalStateException("Precision compositor rejected image: "+count);
            CaptureAudit.precision(count,source,map);
            if(count==0) {
                // Zero above-white pixels does not mean that the original gainmap is empty.
                // Retain its SDR/HDR separation instead of absorbing all gain into the base.
                android.util.Log.i("MiuiHdrHook","Precision gainmap retained: no pixels exceed SDR white; original base, gainmap and metadata preserved");
                return source;
            }
            Gainmap restored=new Gainmap(out);restored.setRatioMin(1,1,1);restored.setRatioMax(params[18],params[19],params[20]);
            restored.setGamma(1,1,1);restored.setEpsilonSdr(1f/64,1f/64,1f/64);restored.setEpsilonHdr(1f/64,1f/64,1f/64);
            restored.setMinDisplayRatioForHdrTransition(gm.getMinDisplayRatioForHdrTransition());
            restored.setDisplayRatioForFullHdr(gm.getDisplayRatioForFullHdr());
            if(Build.VERSION.SDK_INT>=36) restored.setAlternativeImagePrimaries(alternative);
            result.setDensity(source.getDensity());result.setGainmap(restored);out=null;
            android.util.Log.i("MiuiHdrHook","Precision gainmap: highlightPixels="+count+" total="+((long)w*h));
            Bitmap answer=result;result=null;return answer;
        } finally {
            if(base!=null&&base!=source)base.recycle();if(map!=null&&map!=raw)map.recycle();
            if(result!=null)result.recycle();if(out!=null)out.recycle();
        }
    }
}
