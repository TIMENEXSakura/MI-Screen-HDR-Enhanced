package dev.local.recorderlab;
import android.view.Surface;
public final class NativeBridge {
    static { System.loadLibrary("recorderlab"); }
    public static native long create(Surface encoder, int width, int height, int fps, boolean vulkan);
    public static native void configure(long handle, boolean active, long baseTimestamp);
    public static native void setInputWait(long handle, int milliseconds, boolean fatal);
    public static native void requestStop(long handle);
    public static native Surface inputSurface(long handle);
    public static native String stats(long handle);
    public static native String stop(long handle);
}
