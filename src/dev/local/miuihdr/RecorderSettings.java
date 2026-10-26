package dev.local.miuihdr;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Both UIs commit through this provider-owned store; active sessions keep a
 * single snapshot of the choices made before their encoder was created. */
final class RecorderSettings {
    static Bundle read(Context context) {
        synchronized(RecorderModuleBridge.class) {
            SharedPreferences p=context.getSharedPreferences("recorder_test",Context.MODE_PRIVATE);
            Bundle reply=RecorderModuleBridge.snapshot();
            reply.putString("backend",p.getString("backend","gles"));
            reply.putInt("timeout_ms",p.getInt("timeout_ms",2000));
            reply.putBoolean("timeout_fatal",p.getBoolean("timeout_fatal",false));
            reply.putLong("settings_revision",p.getLong("revision",0));
            return reply;
        }
    }
    static Bundle write(Context context,Bundle request) {
        synchronized(RecorderModuleBridge.class) {
            if(request==null)throw new IllegalArgumentException("缺少录屏设置");
            if(RecorderModuleBridge.recording())throw new IllegalStateException("请先结束录屏，再修改设置");
            SharedPreferences prefs=context.getSharedPreferences("recorder_test",Context.MODE_PRIVATE);
            SharedPreferences.Editor edit=prefs.edit().putLong("revision",prefs.getLong("revision",0)+1);
            if(request.containsKey("backend")) {
                String backend=request.getString("backend","");
                if(!RecorderOptions.validBackend(backend))throw new IllegalArgumentException("无效的录屏后端");
                edit.putString("backend",backend);
            }
            if(request.containsKey("timeout_ms")) {
                int wait=request.getInt("timeout_ms",0);
                if(!RecorderOptions.validWait(wait))throw new IllegalArgumentException("无效的帧等待时间");
                edit.putInt("timeout_ms",wait);
            }
            if(request.containsKey("timeout_fatal"))edit.putBoolean("timeout_fatal",request.getBoolean("timeout_fatal"));
            if(!edit.commit())throw new IllegalStateException("录屏设置未能保存，请重试");
            return read(context);
        }
    }
}
