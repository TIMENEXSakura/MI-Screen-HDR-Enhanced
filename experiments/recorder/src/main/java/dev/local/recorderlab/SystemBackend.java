package dev.local.recorderlab;

import android.app.Activity;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Optional root controller. Recording itself does not require root. */
final class SystemBackend {
    static volatile String snapshot = "未主动查询系统后端";
    private final Activity activity;
    private final TextView state;
    private final RadioGroup modes;
    private final Button read, save;
    private boolean busy;
    private static String label(String value) {
        return switch (value) { case "gles" -> "GLES"; case "vulkan" -> "Vulkan"; case "default" -> "系统默认"; default -> "尚未确认"; };
    }
    SystemBackend(Activity activity, LinearLayout parent) {
        this.activity=activity;
        TextView title = new TextView(activity); title.setText("系统底层后端 · 可选测试"); title.setTextSize(20); parent.addView(title);
        TextView explanation = new TextView(activity);
        explanation.setText("与上面的录屏后端分开设置。需要 Root 和配套模块，保存后重启生效，会影响整个系统合成。后端切换只增加启动配置，不再叠加第二份库替换。\n请从 HDR 增强主页安装或更新 1.5.5 底层模块。与原有 HDR 修复共用同一模块。");
        parent.addView(explanation);
        modes=new RadioGroup(activity); modes.setOrientation(RadioGroup.HORIZONTAL);
        for(int i=0;i<3;i++) { RadioButton b=new RadioButton(activity); b.setId(android.view.View.generateViewId()); b.setText(new String[]{"系统默认","GLES","Vulkan"}[i]); modes.addView(b); }
        modes.check(modes.getChildAt(0).getId()); parent.addView(modes);
        read=new Button(activity); read.setText("读取底层状态（Root）"); parent.addView(read);
        save=new Button(activity); save.setText("保存底层选择 · 重启生效"); parent.addView(save); save.setEnabled(false);
        state=new TextView(activity); state.setTextIsSelectable(true); state.setText("底层配置未读取；录屏测试可以直接使用当前系统后端。"); parent.addView(state);
        read.setOnClickListener(v -> operate(null));
        save.setOnClickListener(v -> operate(new String[]{"default","gles","vulkan"}[modes.indexOfChild(modes.findViewById(modes.getCheckedRadioButtonId()))]));
    }
    private void operate(String mode) {
        if(busy) return;
        if(RecordingService.active) { state.setText("请先停止录屏，再修改或读取底层配置。"); return; }
        busy=true; read.setEnabled(false); save.setEnabled(false); state.setText("正在与底层测试模块通讯…");
        new Thread(() -> {
            Map<String,String> result=null; String error=null;
            try { result=exchange(mode); } catch(Exception e) { error=e.getMessage(); }
            Map<String,String> received=result; String failure=error;
            activity.runOnUiThread(() -> {
                busy=false; read.setEnabled(true);
                if(received==null) { state.setText(failure); return; }
                String selected=received.get("selected"), stage=received.get("stage"), actual=received.get("actual");
                modes.check(modes.getChildAt(switch(selected) { case "gles" -> 1; case "vulkan" -> 2; default -> 0; }).getId());
                snapshot = "查询时间=" + new java.util.Date() + ", selected=" + selected + ", actual=" + actual + ", stage=" + stage + ", boot=" + received.get("boot");
                save.setEnabled(!List.of("disabled","removing","prepared").contains(stage));
                String communication=switch(stage) {
                    case "ready" -> "本次开机通讯成功，系统显示进程已稳定";
                    case "prepared" -> "已收到模块回报，等待启动校验";
                    case "disabled" -> "模块已停用";
                    case "removing" -> "模块等待卸载";
                    case "failed" -> "本次启动校验失败";
                    case "stale" -> "系统显示进程已变化，旧回报失效";
                    default -> "模块已安装，等待重启后的通讯回报";
                };
                String detail=switch(received.getOrDefault("result","")) {
                    case "queued" -> "选择已保存，请手动重启手机。";
                    case "verified" -> "所选后端已通过实际运行校验。";
                    case "pending" -> "正在验证启动后的实际后端。";
                    case "unsupported" -> "系统库未检测到所需后端，未写入启动配置。";
                    case "conflict" -> "另一个模块也设置了系统后端，本模块未覆盖。";
                    case "previous_boot_failed","unstable","timeout","not_verified","property_error","disabled" -> "切换未通过校验，已恢复默认选择；请重启后确认实际后端。";
                    default -> "系统默认模式不会覆盖原有系统配置。";
                };
                state.setText("底层已选择："+label(selected)+"\n底层实际运行："+label(actual)+"\n"+communication+"\n"+detail);
            });
        },"system-backend-control").start();
    }
    private Map<String,String> exchange(String mode) throws Exception {
        String challenge=UUID.randomUUID().toString();
        if(mode!=null && !List.of("default","gles","vulkan").contains(mode)) throw new IllegalArgumentException("无效选项");
        String script="[ \"$(id -u)\" = 0 ] || exit 10\n"+
            "for base in /data/adb/modules /data/adb/modules_update; do\n for directory in \"$base\"/*; do\n"+
            "[ -f \"$directory/control.sh\" ] || continue\n"+
            "[ \"$(cat \"$directory/communication.protocol\" 2>/dev/null)\" = mi-screen-hdr-enhanced/2 ] || continue\n"+
            "printf 'current_boot=%s\\n' \"$(cat /proc/sys/kernel/random/boot_id)\"\n"+
            "/system/bin/sh \"$directory/control.sh\" "+(mode==null?"status":"select")+" "+challenge+" "+(mode==null?"":mode)+"\nexit $?\ndone\ndone\nexit 11";
        Process process=new ProcessBuilder("su","-c",script).redirectErrorStream(true).start();
        FutureTask<String> output=new FutureTask<>(() -> {
            try(InputStream in=process.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] bytes=new byte[1024]; int n;
                while((n=in.read(bytes))>=0) { if(out.size()+n>8192) throw new IOException("模块回报过长"); out.write(bytes,0,n); }
                return out.toString(StandardCharsets.UTF_8);
            }
        });
        Thread reader=new Thread(output,"system-backend-response"); reader.setDaemon(true); reader.start();
        try {
            if(!process.waitFor(25,TimeUnit.SECONDS)) throw new IOException("读取超时，请检查 Root 授权。");
            String response=output.get(2,TimeUnit.SECONDS);
            if(process.exitValue()==11) throw new IOException("请返回 HDR 增强主页安装或更新 1.5.5 底层模块，再读取状态。");
            if(process.exitValue()!=0) throw new IOException(response.contains("error=conflict")?"检测到其他模块正在设置系统后端，请先恢复其默认设置。":
                response.contains("error=unsupported")?"系统未检测到所需后端，保留默认配置。":
                response.contains("error=boot_busy")?"系统后端仍在启动校验中，请稍后重试。":
                response.contains("error=disabled")?"请先在 Root 管理器启用配套模块。":"操作失败，请检查 Root 授权和模块状态。");
            Map<String,String> fields=new HashMap<>();
            for(String line:response.split("\n")) {
                if(line.isEmpty()) continue;
                int at=line.indexOf('=');
                if(at<1 || fields.put(line.substring(0,at),line.substring(at+1))!=null) throw new IOException("模块通讯格式异常");
            }
            String uuid="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
            if(!"mi-screen-hdr-enhanced/2".equals(fields.get("protocol")) || !challenge.equals(fields.get("challenge")) ||
                !fields.getOrDefault("installation","").matches(uuid) || !fields.getOrDefault("boot","").matches(uuid) ||
                !fields.get("boot").equals(fields.get("current_boot")) ||
                !fields.getOrDefault("selected","").matches("default|gles|vulkan") ||
                !fields.getOrDefault("actual","").matches("unknown|gles|vulkan") ||
                !fields.getOrDefault("stage","").matches("waiting|prepared|ready|failed|disabled|removing|stale"))
                throw new IOException("模块通讯校验未通过，未将其标记为已生效。");
            return fields;
        } finally { process.destroyForcibly(); output.cancel(true); }
    }
}
