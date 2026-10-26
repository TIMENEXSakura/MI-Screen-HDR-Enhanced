package dev.local.miuihdr;

import android.content.Context;
import android.net.Uri;
import android.os.*;
import java.util.UUID;
import java.util.concurrent.*;

/** The recorder is an unprivileged client; root operations remain in the manager. */
final class RecorderCommunication {
    private static final Uri URI=Uri.parse("content://dev.local.miuihdr.options");
    private static final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"mi-hdr-recorder-ipc");t.setDaemon(true);return t;});
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static Context context;
    private static boolean started;
    static volatile String systemSelected="default", systemActual="unknown", rootStatus="等待底层通讯", message="";
    private static volatile String recorderPhase="loaded", recorderBackend="idle";
    static synchronized void start(Context app,String process) {
        if(started)return;started=true;context=app.getApplicationContext();
        io.scheduleWithFixedDelay(RecorderCommunication::exchange,0,10,TimeUnit.SECONDS);
    }
    static void ping(){if(started)io.execute(RecorderCommunication::exchange);}
    // Xiaomi's UI and :core service are different processes. SharedPreferences
    // caches in the recorder cannot be used to propagate a backend selection.
    static String renderer(Context app) {
        Bundle reply=app.getContentResolver().call(URI,"recorder_settings",null,null);
        String backend=reply==null?"":reply.getString("backend","");
        if(!backend.equals("gles")&&!backend.equals("vulkan"))throw new IllegalStateException("无法读取录屏后端设置");
        return backend;
    }
    static boolean selectRenderer(Context app,String mode) {
        try {
            Bundle request=new Bundle();request.putString("backend",mode);
            Bundle reply=app.getContentResolver().call(URI,"recorder_configure",null,request);
            if(reply==null||!mode.equals(reply.getString("backend")))throw new IllegalStateException("后端设置未确认");
            return true;
        }catch(Throwable e){RecorderHook.notifyError("后端未保存："+e.getMessage());return false;}
    }
    static void selectSystem(String mode){if(started)io.execute(()->{
        try {Bundle request=new Bundle();request.putString("mode",mode);context.getContentResolver().call(URI,"recorder_select",null,request);message="正在保存底层选择，成功后需手动重启。";}
        catch(Throwable e){message="底层选择未保存："+e.getMessage();}
        exchange();
    });}
    private static void exchange() {
        try {
            String nonce=UUID.randomUUID().toString();Bundle request=new Bundle();request.putString("nonce",nonce);
            request.putString("phase",RecorderHook.phase);request.putString("actual",RecorderHook.actual);request.putString("detail",RecorderHook.detail);
            Bundle reply=context.getContentResolver().call(URI,"recorder_ping",null,request);
            if(reply==null || !nonce.equals(reply.getString("nonce")))throw new IllegalStateException("通讯响应未通过校验");
            rootStatus=reply.getString("root_status","等待底层通讯");systemSelected=reply.getString("selected","default");systemActual=reply.getString("actual","unknown");message=reply.getString("message","");
            recorderPhase=reply.getString("recorder_phase","loaded");recorderBackend=reply.getString("recorder_backend","idle");
        }catch(Throwable e){rootStatus="主模块通讯失败："+e.getMessage();recorderPhase="unknown";recorderBackend="idle";}
        main.post(RecorderPreferences::refresh);
    }
    static String summary(){
        boolean recording=RecorderHook.phase.equals("recording")||recorderPhase.equals("recording");
        String backend=RecorderHook.phase.equals("recording")?RecorderHook.actual:recorderBackend;
        String renderer=recording?(backend.equals("vulkan")?"正在使用 Vulkan 录屏":"正在使用小米 GLES 录屏"):
            RecorderHook.phase.equals("error")||recorderPhase.equals("error")?"录屏后端发生错误":
            !RecorderHook.compatible||recorderPhase.equals("unsupported")?"录屏接口未适配":"录屏注入已加载，等待开始录制";
        return rootStatus+"\n"+renderer+"\n系统实际后端："+systemActual+"\n"+message;
    }
}
