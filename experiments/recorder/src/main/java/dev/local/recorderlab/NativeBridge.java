package dev.local.recorderlab;
import android.view.Surface;
final class NativeBridge {
    static { System.loadLibrary("recorderlab"); }
    static native long create(Surface encoder, int width, int height, int fps, boolean vulkan);
    static native Surface inputSurface(long handle);
    static native String stats(long handle);
    static native String stop(long handle);
}
