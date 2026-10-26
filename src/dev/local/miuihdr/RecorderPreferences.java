package dev.local.miuihdr;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import java.lang.reflect.*;
import java.util.*;

/** Uses Xiaomi's existing preference row, spinner and recording-state lock. */
final class RecorderPreferences {
    private static final Map<Object,Object> statusRows=Collections.synchronizedMap(new WeakHashMap<>());
    private static Class<?> preference, list, change;
    private static Method title, summary, entries, values, value, listen, add, screen;
    private static Field key;
    static void install(ClassLoader loader)throws Exception {
        Class<?> fragment=Class.forName("l0.o",false,loader);
        preference=Class.forName("androidx.preference.Preference",false,loader);
        list=Class.forName("com.miui.screenrecorder.view.MiuiScreenRecorderListPreference",false,loader);
        change=Class.forName("androidx.preference.Preference$d",false,loader);
        title=preference.getMethod("F0",CharSequence.class); summary=preference.getMethod("C0",CharSequence.class);
        entries=list.getMethod("h1",CharSequence[].class); values=list.getMethod("j1",CharSequence[].class); value=list.getMethod("k1",String.class);
        listen=preference.getMethod("z0",change);key=RecorderHook.field(preference,"q",String.class);
        screen=fragment.getMethod("g2");add=Class.forName("androidx.preference.PreferenceGroup",false,loader).getMethod("N0",preference);
        HookRuntime.add(RecorderHook.method(fragment,"H0",Bundle.class),new HookRuntime.Callback(){@Override protected void after(HookRuntime.Call call)throws Throwable{
            if(call.hasThrowable() || statusRows.containsKey(call.thisObject))return;
            Object root=screen.invoke(call.thisObject);
            Context context=(Context)preference.getMethod("n").invoke(root);
            Object renderer=newList(context,"mi_hdr_recorder_backend","录屏渲染后端（实验）",
                new String[]{"GLES（小米原生）","Vulkan（实验）"},new String[]{"gles","vulkan"});
            Object handler=Proxy.newProxyInstance(loader,new Class[]{change},(proxy,method,args)->{
                if(method.getDeclaringClass()==Object.class)return objectMethod(proxy,method,args);
                String mode=String.valueOf(args[1]);
                if(!mode.equals("gles")&&!mode.equals("vulkan"))return false;
                if(!RecorderHook.compatible || RecorderHook.phase.equals("recording")){RecorderHook.notifyError("当前无法更改录屏后端");return false;}
                return context.getSharedPreferences("mi_hdr_recorder",0).edit().putString("backend",mode).commit();
            });
            listen.invoke(renderer,handler);add.invoke(root,renderer);
            value.invoke(renderer,context.getSharedPreferences("mi_hdr_recorder",0).getString("backend","gles"));
            summary.invoke(renderer,"下次录屏生效。保留小米的音频、编码和保存流程；Vulkan 失败不会静默回退。");
            Object system=newList(context,"mi_hdr_system_backend","系统合成后端（重启生效）",
                new String[]{"系统默认","GLES","Vulkan"},new String[]{"default","gles","vulkan"});
            listen.invoke(system,Proxy.newProxyInstance(loader,new Class[]{change},(proxy,method,args)->{
                if(method.getDeclaringClass()==Object.class)return objectMethod(proxy,method,args);
                String mode=String.valueOf(args[1]);
                if(!List.of("default","gles","vulkan").contains(mode))return false;
                if(RecorderHook.phase.equals("recording")){RecorderHook.notifyError("请先结束录屏");return false;}
                RecorderCommunication.selectSystem(mode);return false; // Update only after a verified root reply.
            }));
            add.invoke(root,system);value.invoke(system,RecorderCommunication.systemSelected);
            summary.invoke(system,"与 HDR 修复共用底层模块，影响整个系统。保存后请手动重启。");
            Object status=Class.forName("miuix.preference.TextPreference",false,loader).getConstructor(Context.class,AttributeSet.class).newInstance(context,null);
            key.set(status,"mi_hdr_recorder_status");title.invoke(status,"HDR 增强 · 录屏模块状态");summary.invoke(status,RecorderCommunication.summary());add.invoke(root,status);
            statusRows.put(call.thisObject,new Object[]{status,system});
        }});
        HookRuntime.add(RecorderHook.method(fragment,"M0"),new HookRuntime.Callback(){@Override protected void after(HookRuntime.Call call){statusRows.remove(call.thisObject);}});
        HookRuntime.add(RecorderHook.method(fragment,"c1"),new HookRuntime.Callback(){@Override protected void after(HookRuntime.Call call){refresh();RecorderCommunication.ping();}});
    }
    private static Object newList(Context context,String id,String text,String[] labels,String[] codes)throws Exception {
        Object row=list.getConstructor(Context.class,AttributeSet.class).newInstance(context,null);
        key.set(row,id);title.invoke(row,text);entries.invoke(row,(Object)labels);values.invoke(row,(Object)codes);return row;
    }
    static void refresh() {
        synchronized(statusRows){for(Object holder:statusRows.values())try{
            Object[] rows=(Object[])holder;summary.invoke(rows[0],RecorderCommunication.summary());value.invoke(rows[1],RecorderCommunication.systemSelected);
        }catch(Throwable e){HookRuntime.log("Recorder preference status: "+e);}}
    }
    private static Object objectMethod(Object proxy,Method method,Object[] args) {
        return switch(method.getName()){case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->"MiHdrRecorderPreferenceListener";};
    }
}
