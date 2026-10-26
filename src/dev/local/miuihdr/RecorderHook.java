package dev.local.miuihdr;

import android.app.Application;
import android.content.Context;
import android.os.*;
import android.view.Surface;
import android.widget.Toast;
import dev.local.recorderlab.NativeBridge;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Adapter for Xiaomi's EncodeDecodeSurface graph. The native recorder keeps
 * owning its encoder, muxer, audio, controls, output path and recording session. */
final class RecorderHook {
    static volatile Context context;
    static volatile String phase="loaded", detail="", actual="idle";
    static volatile boolean compatible;
    private static final Map<Object,Session> sessions=Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Object> configuring=new ThreadLocal<>();
    private static final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor(r -> {Thread t=new Thread(r,"mi-hdr-recorder-status");t.setDaemon(true);return t;});
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static volatile Session active;
    private static Method callbackStop, callbackError;
    private static Field callback, codecListener;
    private static final class Session {
        long handle, base;
        boolean closed;
        Surface input;
        final WeakReference<Object> owner;
        Session(long h,Surface s,Object recorder) {handle=h;input=s;owner=new WeakReference<>(recorder);}
        synchronized void configure(boolean running,long time) {
            if(closed)return; if(time>0)base=time; NativeBridge.configure(handle,running,base);
        }
        synchronized String stats() {return closed?"{}":NativeBridge.stats(handle);}
        synchronized void close() {
            if(closed)return;closed=true;
            long closing=handle;handle=0;NativeBridge.requestStop(closing);
            // Xiaomi stops its encoder drain loop before releasing its renderer.
            // Return to that cleanup promptly so codec.stop can unblock the WSI.
            Thread cleanup=new Thread(() -> {
                try { HookRuntime.log("Recorder final stats="+NativeBridge.stop(closing)); }
                catch(Throwable e){HookRuntime.log("Recorder cleanup: "+e);}
                finally {if(input!=null){input.release();input=null;}}
            },"mi-hdr-recorder-release");cleanup.setDaemon(true);cleanup.start();
            if(active==this)active=null;
        }
    }
    static Field field(Class<?> type,String name,Class<?> expected)throws Exception {
        Field f=type.getDeclaredField(name);f.setAccessible(true);
        if(expected!=null && f.getType()!=expected)throw new NoSuchFieldException(type.getName()+"."+name+" type");
        return f;
    }
    static Method method(Class<?> type,String name,Class<?>... args)throws Exception {
        Method m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m;
    }
    static void install(ClassLoader loader,String process) {
        try {
            Class<?> application=Class.forName("com.miui.screenrecorder.ScreenRecorderApplication",false,loader);
            HookRuntime.add(method(application,"onCreate"),new HookRuntime.Callback(){@Override protected void after(HookRuntime.Call c){
                context=((Application)c.thisObject).getApplicationContext();
                RecorderCommunication.start(context,process);
            }});
            try {context=(Context)application.getDeclaredMethod("g").invoke(null);if(context!=null)RecorderCommunication.start(context,process);}catch(Throwable ignored){}
            installGraph(loader);
            compatible=true;
            try { RecorderPreferences.install(loader); } catch(Throwable e) {detail="小米设置页接入失败："+e;HookRuntime.log(detail);}
            monitor.scheduleWithFixedDelay(RecorderHook::check,1,2,TimeUnit.SECONDS);
            HookRuntime.log("Xiaomi recorder graph adapter installed; shared backend settings connected");
        }catch(Throwable e){compatible=false;detail="录屏接口未适配："+e;phase="unsupported";HookRuntime.log(detail);}
    }
    private static void installGraph(ClassLoader loader)throws Exception {
        Class<?> surface=Class.forName("g0.a",false,loader), video=Class.forName("i0.n",false,loader), config=Class.forName("i0.m",false,loader);
        Field width=field(surface,"c",int.class), height=field(surface,"d",int.class), input=field(surface,"p",Surface.class);
        Field settings=field(video,"o",config);
        callback=field(surface,"h",null); callbackStop=method(callback.getType(),"b");
        codecListener=field(video,"l",null);callbackError=method(codecListener.getType(),"i",Exception.class);
        Method fps=method(config,"c"), prepare=method(video,"u"), start=method(video,"a"), stop=method(video,"e");
        Method initialize=method(surface,"w",Surface.class), current=method(surface,"z"), texture=method(surface,"E"), release=method(surface,"B"), time=method(surface,"D",long.class), run=method(surface,"F"), halt=method(surface,"H"), finish=method(surface,"G"), getter=method(surface,"y");
        if(fps.getReturnType()!=int.class || getter.getReturnType()!=Surface.class)throw new NoSuchMethodException("Recorder graph shape changed");
        Constructor<?> constructor=surface.getDeclaredConstructor(Surface.class,config,long.class,int.class,callback.getType());
        if(!HookRuntime.deoptimize(constructor)||!HookRuntime.deoptimize(prepare)||!HookRuntime.deoptimize(start)||!HookRuntime.deoptimize(stop))
            throw new IllegalStateException("无法确认录屏调用链去优化，保留原生后端");
        // Resolve the whole adapter before installing interception points.
        HookRuntime.add(prepare,new HookRuntime.Callback(){
            @Override protected void before(HookRuntime.Call c){configuring.set(c.thisObject);phase="prepared";detail="";RecorderCommunication.ping();}
            @Override protected void after(HookRuntime.Call c){configuring.remove();if(c.hasThrowable()){phase="error";detail="小米录屏准备失败";}RecorderCommunication.ping();}
        });
        HookRuntime.add(initialize,new HookRuntime.Callback(){@Override protected void before(HookRuntime.Call c){
            Context app=context;
            if(!compatible || app==null)return;
            try {
                Bundle options=RecorderCommunication.settings(app,false);
                if(!"vulkan".equals(options.getString("backend")))return;
                Object recorder=configuring.get();if(recorder==null)throw new IllegalStateException("Unknown encoder construction path");
                int rate=(Integer)fps.invoke(settings.get(recorder));
                long handle=NativeBridge.create((Surface)c.args[0],width.getInt(c.thisObject),height.getInt(c.thisObject),rate,true);
                try {
                    NativeBridge.setInputWait(handle,options.getInt("timeout_ms",2000),options.getBoolean("timeout_fatal",false));
                    NativeBridge.configure(handle,false,0);
                    Session session=new Session(handle,NativeBridge.inputSurface(handle),recorder);
                    sessions.put(c.thisObject,session); input.set(c.thisObject,session.input);
                }catch(Throwable e){NativeBridge.stop(handle);throw e;}
                c.setResult(null);phase="prepared";actual="vulkan";detail="Vulkan 后端已初始化，等待开始录制";
            }catch(Throwable e){
                phase="error";actual="idle";detail=e.toString();
                c.setThrowable(new IllegalStateException("无法启用所选录屏后端；未静默切换后端",e));notifyError(detail);
            }
        }});
        for(Method target:new Method[]{current,texture})HookRuntime.add(target,new HookRuntime.Callback(){@Override protected void before(HookRuntime.Call c){if(sessions.containsKey(c.thisObject))c.setResult(null);}});
        HookRuntime.add(time,new HookRuntime.Callback(){@Override protected void before(HookRuntime.Call c){Session s=sessions.get(c.thisObject);if(s!=null){s.configure(false,(Long)c.args[0]);c.setResult(null);}}});
        HookRuntime.add(run,new HookRuntime.Callback(){@Override protected void before(HookRuntime.Call c){Session s=sessions.get(c.thisObject);if(s!=null){s.configure(true,0);active=s;phase="recording";actual="vulkan";c.setResult(null);}}});
        HookRuntime.add(halt,new HookRuntime.Callback(){@Override protected void before(HookRuntime.Call c){Session s=sessions.get(c.thisObject);if(s!=null){s.configure(false,0);c.setResult(null);}}});
        HookRuntime.add(release,new HookRuntime.Callback(){@Override protected void before(HookRuntime.Call c){Session s=sessions.get(c.thisObject);if(s!=null){s.close();c.setResult(null);}}});
        HookRuntime.add(finish,new HookRuntime.Callback(){@Override protected void before(HookRuntime.Call c)throws Throwable{
            Session s=sessions.get(c.thisObject);if(s==null)return;
            s.close();c.setResult(null);
            try {callbackStop.invoke(callback.get(c.thisObject));}catch(Throwable e){HookRuntime.log("Recorder stop callback: "+e);}
        }});
        HookRuntime.add(start,new HookRuntime.Callback(){@Override protected void after(HookRuntime.Call c){
            if(c.hasThrowable()){phase="error";detail="小米录屏未能开始";RecorderCommunication.ping();return;}
            phase="recording";if(active==null)actual="gles";
            RecorderCommunication.ping();
        }});
        HookRuntime.add(stop,new HookRuntime.Callback(){@Override protected void after(HookRuntime.Call c){phase="idle";actual="idle";RecorderCommunication.ping();}});
    }
    private static void check() {
        try {
            Session session=active;if(session==null)return;
            String stats=session.stats();detail=stats;
            String error=new JSONObject(stats).optString("error");
            if(error.isEmpty())return;
            phase="error";actual="idle";session.configure(false,0);active=null;notifyError(error);
            Object recorder=session.owner.get();
            if(recorder!=null){Object listener=codecListener.get(recorder);if(listener!=null)callbackError.invoke(listener,new IllegalStateException("Vulkan: "+error));}
        }catch(Throwable e){HookRuntime.log("Recorder monitor: "+e);}
    }
    static void notifyError(String error){Context app=context;if(app!=null)main.post(() -> Toast.makeText(app,"HDR 录屏测试："+error,Toast.LENGTH_LONG).show());}
}
