package dev.local.miuihdr;

/** Shared values for both settings surfaces. Protocol keys stay stable. */
final class RecorderOptions {
    static String label(String value) {
        return switch(value) {
            case "gles" -> "OpenGL ES";
            case "vulkan" -> "Vulkan";
            case "default" -> "系统默认";
            case "idle" -> "未在录屏";
            default -> "尚未确认";
        };
    }
    static boolean validBackend(String mode) {return "gles".equals(mode)||"vulkan".equals(mode);}
    static boolean validWait(int ms) {return ms==500||ms==1000||ms==2000||ms==5000||ms==10000;}
    static boolean recording(String phase) {return "recording".equals(phase)||"prepared".equals(phase);}
    static String waitLabel(int ms) {return ms==500?"0.5 秒":(ms/1000)+" 秒";}
}
