package dev.local.miuihdr;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Device test: HDR classification reads raw gain samples without alpha modulation. */
public final class HdrSignalTest {
    private static int passed;

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        passed++;
        System.out.println("PASS " + description);
    }

    private static byte[] pixels(Bitmap bitmap) {
        ByteBuffer bytes = ByteBuffer.allocate(bitmap.getByteCount());
        bitmap.copyPixelsToBuffer(bytes);
        return bytes.array();
    }

    private static void fixture(String name, boolean alphaOnly, boolean premultiplied,
            int red, int green, int blue, int alpha, float headroom, int expectedRaw) {
        final int width = 7, height = 5;
        Bitmap map = Bitmap.createBitmap(width, height,
                alphaOnly ? Bitmap.Config.ALPHA_8 : Bitmap.Config.ARGB_8888);
        Bitmap base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888,
                false, ColorSpace.get(ColorSpace.Named.SRGB));
        try {
            if (!alphaOnly) {
                map.setHasAlpha(true);
                map.setPremultiplied(premultiplied);
            }
            // Test the last pixel, including A8 row padding. setPixel/Canvas would
            // apply alpha conversion and hide the raw RGBA cases under test.
            ByteBuffer bytes = ByteBuffer.allocate(map.getByteCount());
            int offset = (height - 1) * map.getRowBytes()
                    + (width - 1) * (alphaOnly ? 1 : 4);
            if (alphaOnly) {
                bytes.put(offset, (byte) alpha);
            } else {
                bytes.put(offset, (byte) red);
                bytes.put(offset + 1, (byte) green);
                bytes.put(offset + 2, (byte) blue);
                bytes.put(offset + 3, (byte) alpha);
            }
            bytes.rewind();
            map.copyPixelsFromBuffer(bytes);
            base.eraseColor(Color.rgb(96, 96, 96));
            Gainmap gain = new Gainmap(map);
            gain.setRatioMin(1, 1, 1);
            gain.setRatioMax(headroom, headroom, headroom);
            gain.setGamma(1, 1, 1);
            gain.setEpsilonSdr(1f / 64, 1f / 64, 1f / 64);
            gain.setEpsilonHdr(1f / 64, 1f / 64, 1f / 64);
            gain.setMinDisplayRatioForHdrTransition(1);
            gain.setDisplayRatioForFullHdr(headroom);
            base.setGainmap(gain);
            byte[] originalBase = pixels(base), originalMap = pixels(map);

            // A direct call prevents a broken JNI implementation from silently
            // falling back to Java and making the comparison pass accidentally.
            NativeBridge.loaded = true;
            check(NativeBridge.firstSignal(map) == expectedRaw, name + ": JNI raw sample");
            HdrSignal.invalidate(base);
            HdrSignal.Info nativeResult = HdrSignal.inspect(base);
            boolean expectedHdr = headroom > 1 && expectedRaw > 0;
            int expectedSignal = headroom > 1 ? expectedRaw : 0;
            check(nativeResult.hdr == expectedHdr && nativeResult.peak == expectedSignal,
                    name + ": JNI classification includes an isolated nonzero pixel");

            NativeBridge.loaded = false;
            HdrSignal.invalidate(base);
            HdrSignal.Info javaResult = HdrSignal.inspect(base);
            check(javaResult.hdr == expectedHdr && javaResult.peak == expectedSignal,
                    name + ": Java fallback preserves raw sample semantics");
            check(nativeResult.hdr == javaResult.hdr && nativeResult.peak == javaResult.peak
                            && nativeResult.ratio == javaResult.ratio,
                    name + ": JNI and Java fallback agree");
            check(Arrays.equals(originalBase, pixels(base))
                            && Arrays.equals(originalMap, pixels(map))
                            && base.hasGainmap()
                            && base.getGainmap().getDisplayRatioForFullHdr() == headroom,
                    name + ": classification leaves both image planes and headroom unchanged");
        } finally {
            NativeBridge.loaded = true;
            HdrSignal.invalidate(base);
            base.recycle();
            map.recycle();
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException("Expected native library path");
            System.load(args[0]);
            NativeBridge.loaded = true;
            fixture("single brightest red pixel", false, true, 255, 0, 0, 255, 4, 255);
            fixture("single weakest blue sample", false, true, 0, 0, 1, 255, 4, 1);
            fixture("headroom 1.005", false, true, 0, 255, 0, 255, 1.005f, 255);
            fixture("unpremultiplied RGB with zero alpha", false, false, 255, 0, 0, 0, 4, 255);
            fixture("unpremultiplied RGB with alpha one", false, false, 0, 255, 0, 1, 4, 255);
            fixture("premultiplied stored RGB one alpha one", false, true, 1, 1, 1, 1, 4, 1);
            fixture("RGBA alpha is not gain", false, true, 0, 0, 0, 255, 4, 0);
            fixture("A8 alpha one is gain", true, true, 0, 0, 0, 1, 4, 1);
            fixture("empty RGBA gainmap", false, true, 0, 0, 0, 0, 4, 0);
            fixture("empty A8 gainmap", true, true, 0, 0, 0, 0, 4, 0);
            fixture("unit headroom stays SDR", false, true, 255, 255, 255, 255, 1, 255);
            System.out.println("HDR_SIGNAL_CHECKS=" + passed);
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
