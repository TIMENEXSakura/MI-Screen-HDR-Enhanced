package dev.local.miuihdr;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import java.lang.reflect.*;
import java.util.*;

/** Native Xiaomi rows and dropdowns, sharing confirmed settings with the manager. */
final class RecorderPreferences {
    private record Rows(Object status,Object renderer,Object system,Object inputWait,Object policy){}
    private static final Map<Object,Rows> rows=Collections.synchronizedMap(new WeakHashMap<>());
    private static Class<?> preference,list,change;
    private static Method title,summary,entries,values,value,listen,add,screen;
    private static Field key;
    static void install(ClassLoader loader)throws Exception {
        Class<?> fragment=Class.forName("l0.o",false,loader);
        preference=Class.forName("androidx.preference.Preference",false,loader);
        list=Class.forName("com.miui.screenrecorder.view.MiuiScreenRecorderListPreference",false,loader);
        change=Class.forName("androidx.preference.Preference$d",false,loader);
        title=preference.getMethod("F0",CharSequence.class);summary=preference.getMethod("C0",CharSequence.class);
        entries=list.getMethod("h1",CharSequence[].class);values=list.getMethod("j1",CharSequence[].class);value=list.getMethod("k1",String.class);
        listen=preference.getMethod("z0",change);key=RecorderHook.field(preference,"q",String.class);
        screen=fragment.getMethod("g2");add=Class.forName("androidx.preference.PreferenceGroup",false,loader).getMethod("N0",preference);
        Constructor<?> statusConstructor=Class.forName("miuix.preference.TextPreference",false,loader).getConstructor(Context.class,AttributeSet.class);
        Constructor<?> categoryConstructor=Class.forName("miuix.preference.PreferenceCategory",false,loader).getConstructor(Context.class,AttributeSet.class);
        HookRuntime.add(RecorderHook.method(fragment,"H0",Bundle.class),new HookRuntime.Callback(){@Override protected void after(HookRuntime.Call call)throws Throwable {
            if(call.hasThrowable()||rows.containsKey(call.thisObject))return;
            Object root=screen.invoke(call.thisObject);
            Context context=(Context)preference.getMethod("n").invoke(root);
            RecorderCommunication.settings(context,true);
            Object group=categoryConstructor.newInstance(context,null);key.set(group,"mi_hdr_recorder_group");title.invoke(group,"录屏增强");add.invoke(root,group);
            Object renderer=newList(context,"backend","录屏后端",new String[]{"OpenGL ES（小米原生）","Vulkan（实验）"},new String[]{"gles","vulkan"});
            bind(loader,context,renderer,"backend");add.invoke(group,renderer);
            Object system=newList(context,"system","系统合成后端",new String[]{"系统默认","OpenGL ES","Vulkan"},new String[]{"default","gles","vulkan"});
            bind(loader,context,system,"system");add.invoke(group,system);
            Object status=statusConstructor.newInstance(context,null);key.set(status,"mi_hdr_recorder_status");title.invoke(status,"运行状态");add.invoke(group,status);
            Object special=categoryConstructor.newInstance(context,null);key.set(special,"mi_hdr_recorder_special");title.invoke(special,"特殊设置 · Vulkan");add.invoke(root,special);
            Object wait=newList(context,"timeout_ms","输入帧等待时间",new String[]{"0.5 秒","1 秒","2 秒（默认）","5 秒","10 秒"},new String[]{"500","1000","2000","5000","10000"});
            bind(loader,context,wait,"timeout_ms");add.invoke(special,wait);
            Object policy=newList(context,"timeout_fatal","输入帧超时处理",new String[]{"跳过超时帧，继续录屏（默认）","停止录屏"},new String[]{"continue","stop"});
            bind(loader,context,policy,"timeout_fatal");add.invoke(special,policy);
            rows.put(call.thisObject,new Rows(status,renderer,system,wait,policy));refresh();
        }});
        HookRuntime.add(RecorderHook.method(fragment,"M0"),new HookRuntime.Callback(){@Override protected void after(HookRuntime.Call call){rows.remove(call.thisObject);}});
        HookRuntime.add(RecorderHook.method(fragment,"c1"),new HookRuntime.Callback(){@Override protected void after(HookRuntime.Call call){refresh();RecorderCommunication.ping();}});
    }
    private static void bind(ClassLoader loader,Context context,Object row,String option)throws Exception {
        listen.invoke(row,Proxy.newProxyInstance(loader,new Class[]{change},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return objectMethod(proxy,method,args);
            try {
                Bundle state=RecorderCommunication.current();
                if(RecorderOptions.recording(RecorderHook.phase)||state.getBoolean("recording"))throw new IllegalStateException("请先结束录屏，再修改设置");
                String mode=String.valueOf(args[1]);
                if(option.equals("system")) {
                    if(state.getBoolean("system_saving")||!state.getBoolean("root_fresh")||!"ready".equals(state.getString("root_stage")))throw new IllegalStateException("请等待底层模块完成连接");
                    RecorderCommunication.selectSystem(mode);return false;
                }
                if(!RecorderHook.compatible)throw new IllegalStateException("当前小米录屏版本暂未适配");
                Bundle request=new Bundle();
                switch(option){
                    case "timeout_ms"->request.putInt(option,Integer.parseInt(mode));
                    case "timeout_fatal"->request.putBoolean(option,mode.equals("stop"));
                    default->request.putString(option,mode);
                }
                RecorderCommunication.save(context,request);refresh();return true;
            }catch(Throwable e){RecorderHook.notifyError("设置未保存："+e.getMessage());return false;}
        }));
    }
    private static Object newList(Context context,String id,String text,String[] labels,String[] codes)throws Exception {
        Object row=list.getConstructor(Context.class,AttributeSet.class).newInstance(context,null);
        key.set(row,"mi_hdr_recorder_"+id);title.invoke(row,text);entries.invoke(row,(Object)labels);values.invoke(row,(Object)codes);return row;
    }
    static void refresh() {
        Bundle s=RecorderCommunication.current();
        synchronized(rows){for(Rows r:rows.values())try {
            value.invoke(r.renderer,s.getString("backend","gles"));
            summary.invoke(r.renderer,RecorderOptions.label(s.getString("backend","gles"))+" · 下次录屏生效，与 HDR 增强设置同步");
            value.invoke(r.system,s.getString("selected","default"));
            summary.invoke(r.system,s.getBoolean("system_saving")?"正在保存…":"已选择："+RecorderOptions.label(s.getString("selected","default"))+"；实际："+RecorderOptions.label(s.getString("actual","unknown"))+"。更改后需重启手机。");
            value.invoke(r.inputWait,Integer.toString(s.getInt("timeout_ms",2000)));
            summary.invoke(r.inputWait,RecorderOptions.waitLabel(s.getInt("timeout_ms",2000))+" · 等待画面就绪的最长时间，下次录屏生效");
            value.invoke(r.policy,s.getBoolean("timeout_fatal")?"stop":"continue");
            summary.invoke(r.policy,s.getBoolean("timeout_fatal")?"输入帧等待超时会结束录制":"超时仅记录提示，跳过该帧并继续录制");
            summary.invoke(r.status,RecorderCommunication.summary());
        }catch(Throwable e){HookRuntime.log("Recorder preference status: "+e);}}
    }
    private static Object objectMethod(Object proxy,Method method,Object[] args) {
        return switch(method.getName()){case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->"MiHdrRecorderPreferenceListener";};
    }
}
