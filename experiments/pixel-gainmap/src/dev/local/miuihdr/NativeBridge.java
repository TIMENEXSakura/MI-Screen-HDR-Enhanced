package dev.local.miuihdr;
import android.graphics.Bitmap;
final class NativeBridge {
 static boolean loaded;
 static native int rebaseGainmap(Bitmap source, Bitmap gain, Bitmap result, Bitmap output, float[] params, float[] transfer);
}
