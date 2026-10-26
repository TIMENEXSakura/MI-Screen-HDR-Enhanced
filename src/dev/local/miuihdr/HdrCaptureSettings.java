package dev.local.miuihdr;

import android.content.Context;
import android.os.Bundle;
import org.json.JSONObject;
import org.json.JSONArray;

/** Bounded metadata diagnostics; never stores captured pixels. */
final class HdrCaptureSettings {
    static synchronized Bundle read(Context context) {
        var prefs=context.getSharedPreferences("ui_options",Context.MODE_PRIVATE);
        Bundle reply=new Bundle();
        reply.putString("hdr_capture_mode",mode(prefs.getString("hdr_capture_mode","native")));
        reply.putBoolean("hdr_capture_rgb",prefs.getBoolean("hdr_capture_rgb",true));
        reply.putString("hdr_capture_report",prefs.getString("hdr_capture_report",""));
        reply.putString("hdr_capture_history",prefs.getString("hdr_capture_history","[]"));
        return reply;
    }
    static String mode(String value){return "display".equals(value)||"compat".equals(value)?value:"native";}
    static synchronized Bundle save(Context context,Bundle request) {
        if(request==null)throw new IllegalArgumentException("缺少 HDR 设置");
        var edit=context.getSharedPreferences("ui_options",Context.MODE_PRIVATE).edit();
        if(request.containsKey("hdr_capture_mode")){
            String value=request.getString("hdr_capture_mode","");
            if(!value.equals(mode(value)))throw new IllegalArgumentException("无效的 HDR 取帧方式");
            edit.putString("hdr_capture_mode",value);
        }
        if(request.containsKey("hdr_capture_rgb"))edit.putBoolean("hdr_capture_rgb",request.getBoolean("hdr_capture_rgb"));
        if(!edit.commit())throw new IllegalStateException("HDR 设置保存失败");
        return read(context);
    }
    static synchronized Bundle report(Context context,Bundle request) {
        String raw=request==null?"":request.getString("report","");
        if(raw.length()>8192)throw new IllegalArgumentException("HDR 诊断内容过长");
        try {
            JSONObject item=new JSONObject(raw);var prefs=context.getSharedPreferences("ui_options",Context.MODE_PRIVATE);
            JSONArray old=new JSONArray(prefs.getString("hdr_capture_history","[]")),history=new JSONArray();
            for(int i=Math.max(0,old.length()-7);i<old.length();i++)history.put(old.get(i));history.put(item);
            if(!prefs.edit().putString("hdr_capture_report",item.toString()).putString("hdr_capture_history",history.toString()).commit())throw new IllegalStateException("HDR 诊断保存失败");
            Bundle reply=new Bundle();reply.putBoolean("saved",true);return reply;
        }catch(org.json.JSONException e){throw new IllegalArgumentException("HDR 诊断格式错误",e);}
    }
}
