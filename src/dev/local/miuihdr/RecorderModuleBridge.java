package dev.local.miuihdr;

import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Root communication is performed only in the HDR manager's provider process. */
final class RecorderModuleBridge {
    private static final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"hdr-module-channel");t.setDaemon(true);return t;});
    private static final Map<Integer,Child> children=new HashMap<>();
    private static Map<String,String> root=Map.of();
    private static String message="", rootStatus="等待底层模块回报";
    private static long lastRoot, lastAttempt=-10000;
    private static boolean busy;
    private record Child(int pid,String phase,String backend,long time){}
    private static Child currentChild(long now) {
        return children.values().stream().filter(c->now-c.time<30000)
            .max(Comparator.<Child>comparingInt(c->switch(c.phase){
                case "recording"->5;case "error"->4;case "unsupported"->3;case "prepared"->2;case "idle"->1;default->0;
            }).thenComparingLong(c->c.time)).orElse(null);
    }
    static synchronized boolean recording() {
        return children.values().stream().anyMatch(c->c.phase.equals("recording")&&SystemClock.elapsedRealtime()-c.time<30000);
    }
    static synchronized void refresh() {
        long now=SystemClock.elapsedRealtime();
        if(busy || now-lastAttempt<10000)return;
        busy=true;lastAttempt=now;io.execute(()->update("status",""));
    }
    static synchronized String summary() {
        long now=SystemClock.elapsedRealtime();
        Child child=currentChild(now);
        String recorder=child==null?"等待小米录屏回报":child.phase.equals("recording")?"正在使用 "+child.backend+" 录屏":child.phase.equals("unsupported")?"录屏接口未适配":child.phase.equals("error")?"录屏后端异常":"录屏注入已加载";
        return (now-lastRoot>35000?"底层通讯等待验证":rootStatus)+"\n"+recorder;
    }
    static synchronized Bundle ping(int pid,Bundle request) {
        String nonce=request==null?"":request.getString("nonce","");
        if(!nonce.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid nonce");
        String phase=request.getString("phase","loaded"),backend=request.getString("actual","idle");
        if(!List.of("loaded","prepared","recording","idle","error","unsupported").contains(phase) || !List.of("gles","vulkan","idle").contains(backend))throw new IllegalArgumentException("Invalid recorder status");
        children.put(pid,new Child(pid,phase,backend,SystemClock.elapsedRealtime()));
        children.values().removeIf(c->SystemClock.elapsedRealtime()-c.time>30000);
        Child child=currentChild(SystemClock.elapsedRealtime());
        if(!busy){busy=true;lastAttempt=SystemClock.elapsedRealtime();io.execute(()->update("report",child.phase+" "+child.backend+" "+child.pid));}
        Bundle response=new Bundle();response.putString("nonce",nonce);
        response.putString("recorder_phase",child.phase);response.putString("recorder_backend",child.backend);
        response.putString("root_status",SystemClock.elapsedRealtime()-lastRoot>35000?"底层通讯等待验证":rootStatus);
        response.putString("selected",root.getOrDefault("selected","default"));response.putString("actual",root.getOrDefault("actual","unknown"));response.putString("message",message);
        return response;
    }
    static synchronized Bundle select(Bundle request) {
        String mode=request==null?"":request.getString("mode","");
        if(!List.of("default","gles","vulkan").contains(mode))throw new IllegalArgumentException("Invalid backend");
        if(recording())throw new IllegalStateException("请先停止录屏");
        if(busy)throw new IllegalStateException("底层通讯进行中，请稍后重试");
        busy=true;message="正在保存底层选择";io.execute(()->update("select",mode));
        Bundle reply=new Bundle();reply.putBoolean("queued",true);return reply;
    }
    private static void update(String action,String args) {
        try {
            Map<String,String> result=exchange(action,args);
            synchronized(RecorderModuleBridge.class){
                root=result;lastRoot=SystemClock.elapsedRealtime();
                rootStatus=switch(result.get("stage")){
                    case "ready"->"底层模块已安装，本次开机通讯成功";
                    case "prepared"->"底层模块已安装，正在校验启动";
                    case "disabled"->"底层模块已停用";
                    case "removing"->"底层模块等待卸载";
                    case "stale"->"底层进程已变化，请重启后重新校验";
                    default->"底层模块已安装，等待本次开机回报";
                };
                message=switch(result.getOrDefault("result","")){
                    case "queued"->"底层选择已保存，请手动重启手机。";
                    case "verified"->"所选系统后端已通过运行校验。";
                    case "pending"->"系统后端正在启动校验中。";
                    case "not_verified","previous_boot_failed","failed","property_error"->"后端切换未通过校验，已撤销选择，请重启确认。";
                    case "unsupported"->"系统未检测到所需后端，保留默认。";
                    case "conflict"->"其他模块也配置了系统后端，本模块未覆盖。";
                    default->"系统默认模式不覆盖原有后端配置。";
                };
                if("online".equals(result.get("child")))rootStatus+="；录屏子模块回报已收到";
            }
        }catch(Exception e){synchronized(RecorderModuleBridge.class){rootStatus="底层通讯未完成";message=e.getMessage();lastRoot=0;}}
        finally{synchronized(RecorderModuleBridge.class){busy=false;}}
    }
    private static Map<String,String> exchange(String action,String args)throws Exception {
        String nonce=UUID.randomUUID().toString();
        String script="[ \"$(id -u)\" = 0 ] || exit 10\n"+
            "for base in /data/adb/modules /data/adb/modules_update; do\nfor directory in \"$base\"/*; do\n"+
            "[ -f \"$directory/control.sh\" ] || continue\n"+
            "[ \"$(cat \"$directory/communication.protocol\" 2>/dev/null)\" = mi-screen-hdr-enhanced/2 ] || continue\n"+
            "printf 'current_boot=%s\\n' \"$(cat /proc/sys/kernel/random/boot_id)\"\n"+
            "/system/bin/sh \"$directory/control.sh\" "+action+" "+nonce+" "+args+"\nexit $?\ndone\ndone\nexit 11";
        java.lang.Process process=new ProcessBuilder("su","-c",script).redirectErrorStream(true).start();
        FutureTask<String> output=new FutureTask<>(()->{
            try(InputStream in=process.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] block=new byte[1024];int n;while((n=in.read(block))>=0){if(out.size()+n>8192)throw new IOException("模块回报异常");out.write(block,0,n);}return out.toString(StandardCharsets.UTF_8);
            }
        });
        Thread reader=new Thread(output,"hdr-module-response");reader.setDaemon(true);reader.start();
        try {
            if(!process.waitFor(15,TimeUnit.SECONDS))throw new IOException("底层读取超时，请检查 HDR 增强的 Root 授权");
            String response=output.get(2,TimeUnit.SECONDS);
            if(process.exitValue()==11)throw new IOException("请从 HDR 增强主页安装或更新 1.5.5 底层模块");
            if(process.exitValue()!=0)throw new IOException(response.contains("error=unsupported")?"未检测到所需系统后端":response.contains("error=conflict")?"其他模块正在设置系统后端":response.contains("error=boot_busy")?"启动校验尚未完成":response.contains("error=disabled")?"底层模块已停用":"操作未完成，请检查 Root 授权与模块状态");
            Map<String,String> values=new HashMap<>();
            for(String line:response.split("\n")){if(line.isEmpty())continue;int at=line.indexOf('=');if(at<1||values.put(line.substring(0,at),line.substring(at+1))!=null)throw new IOException("通讯格式异常");}
            String uuid="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
            if(!"mi-screen-hdr-enhanced/2".equals(values.get("protocol"))||!nonce.equals(values.get("challenge"))||
                !values.getOrDefault("installation","").matches(uuid)||!values.getOrDefault("boot","").matches(uuid)||!values.get("boot").equals(values.get("current_boot"))||
                !values.getOrDefault("stage","").matches("waiting|prepared|ready|stale|disabled|removing")||!values.getOrDefault("actual","").matches("unknown|gles|vulkan")||!values.getOrDefault("selected","").matches("default|gles|vulkan"))throw new IOException("底层通讯校验失败");
            return values;
        }finally{process.destroyForcibly();output.cancel(true);}
    }
}
