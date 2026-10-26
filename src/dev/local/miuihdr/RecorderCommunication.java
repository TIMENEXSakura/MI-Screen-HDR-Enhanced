package dev.local.miuihdr;

import android.content.Context;
import android.net.Uri;
import android.os.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Both settings screens use the same provider; recorder processes never run su. */
final class RecorderCommunication {
    private static final Uri URI=Uri.parse("content://dev.local.miuihdr.options");
    private static final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"mi-hdr-recorder-ipc");t.setDaemon(true);return t;});
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static Context context;
    private static boolean started;
    private static Bundle state=new Bundle();
    static synchronized Bundle current(){return new Bundle(state);}
    private static synchronized Bundle adopt(Bundle reply) {
        if(reply==null)throw new IllegalStateException("未收到主模块的设置回报");
        Bundle next=new Bundle(reply);
        // A heartbeat already in flight must not undo a newer confirmed save.
        if(next.getLong("settings_revision",-1)<state.getLong("settings_revision",-1)) {
            next.putString("backend",state.getString("backend"));
            next.putInt("timeout_ms",state.getInt("timeout_ms",2000));
            next.putBoolean("timeout_fatal",state.getBoolean("timeout_fatal",false));
            next.putLong("settings_revision",state.getLong("settings_revision"));
        }
        state=next;return new Bundle(state);
    }
    static Bundle settings(Context app,boolean refresh) {
        Bundle request=new Bundle();request.putBoolean("refresh",refresh);
        return adopt(app.getContentResolver().call(URI,"recorder_settings",null,request));
    }
    static Bundle save(Context app,Bundle request) {
        return adopt(app.getContentResolver().call(URI,"recorder_configure",null,request));
    }
    static Bundle saveSystem(Context app,String mode) {
        Bundle request=new Bundle();request.putString("mode",mode);
        Bundle reply=app.getContentResolver().call(URI,"recorder_select",null,request);
        if(reply==null||!reply.getBoolean("queued"))throw new IllegalStateException("系统后端设置未被接收");
        return settings(app,false);
    }
    static synchronized void start(Context app,String process) {
        if(started)return;started=true;context=app.getApplicationContext();
        io.scheduleWithFixedDelay(RecorderCommunication::exchange,0,10,TimeUnit.SECONDS);
    }
    static void ping(){if(started)io.execute(RecorderCommunication::exchange);}
    static void selectSystem(String mode){if(started)io.execute(()->{
        try {saveSystem(context,mode);}catch(Throwable e){RecorderHook.notifyError("系统后端未保存："+e.getMessage());}
        exchange();
    });}
    private static void exchange() {
        try {
            String nonce=UUID.randomUUID().toString();Bundle request=new Bundle();request.putString("nonce",nonce);
            request.putString("phase",RecorderHook.phase);request.putString("actual",RecorderHook.actual);request.putString("detail",RecorderHook.detail);
            Bundle reply=context.getContentResolver().call(URI,"recorder_ping",null,request);
            if(reply==null||!nonce.equals(reply.getString("nonce")))throw new IllegalStateException("通讯响应未通过校验");
            adopt(reply);
        }catch(Throwable e){synchronized(RecorderCommunication.class){
            state.putString("root_status","主模块通讯失败："+e.getMessage());state.putBoolean("root_fresh",false);
            state.putString("recorder_phase","unknown");state.putString("actual","unknown");
        }}
        main.post(RecorderPreferences::refresh);
    }
    static String recordingLabel(Bundle s) {
        return switch(s.getString("recorder_phase","unknown")) {
            case "recording"->"正在使用 "+RecorderOptions.label(s.getString("recorder_backend","idle"))+" 录屏";
            case "prepared"->"正在准备录屏";
            case "error"->"录屏后端发生错误";
            case "unsupported"->"当前小米录屏版本暂未适配";
            case "loaded","idle"->"录屏注入已加载，等待开始录制";
            default->"等待小米录屏连接";
        };
    }
    static String timeoutSummary(Bundle s) {
        try {long count=new JSONObject(s.getString("recorder_detail","")).optLong("fence_timeouts");
            return count>0?"本次录制遇到 "+count+" 次输入帧等待超时":"";
        }catch(Exception ignored){return "";}
    }
    static String summary() {
        Bundle s=current();String timeouts=timeoutSummary(s);
        return recordingLabel(s)+"\n"+s.getString("root_status","等待底层通讯")+
            "\n系统实际后端："+RecorderOptions.label(s.getString("actual","unknown"))+
            (s.getBoolean("system_saving")?"\n正在保存系统后端…":"\n"+s.getString("message",""))+
            (s.getString("selection_error","").isEmpty()?"":"\n"+s.getString("selection_error"))+
            (timeouts.isEmpty()?"":"\n"+timeouts);
    }
}
