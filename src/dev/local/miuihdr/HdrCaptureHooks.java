package dev.local.miuihdr;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Scope modifications to Xiaomi's existing capture, retaining all exclusion,
 * crop and security arguments. Never combine pixels from separate captures. */
final class HdrCaptureHooks {
    private static final String DATASPACE="dev.local.miuihdr.capture.dataspace";
    private static final ThreadLocal<Scope> ACTIVE=new ThreadLocal<>();
    private static final Map<Class<?>,Adapter> ADAPTERS=new HashMap<>();
    private static final ExecutorService REPORTS=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"hdr-capture-report");t.setDaemon(true);return t;});
    private static final class Scope {
        final Scope previous;final Context context;final int display;final HookConfig config;final long started=SystemClock.elapsedRealtime();
        final JSONObject report=new JSONObject();boolean preparing,retrying,changed,blocked;Adapter adapter;Object originalArgs;
        Scope(Context c,int id){previous=ACTIVE.get();context=c.getApplicationContext();display=id;config=HookConfig.read(c);}
        boolean requested(){return !"native".equals(config.hdrCaptureMode);}
        void put(String key,Object value){try{report.put(key,value);}catch(JSONException ignored){}}
    }
    private static final class Adapter {
        final Class<?> bufferType,argsType,listenerType,captureType;final Method build,factory,asBitmap,getHardware,secure,hdr;
        final Field format,preserve,miMode,ratio;final Method syncListener,wmsCapture;
        boolean ready;
        Adapter(Class<?> buffer)throws Exception {
            bufferType=buffer;String prefix=buffer.getName().substring(0,buffer.getName().indexOf('$'));
            captureType=Class.forName(prefix);argsType=Class.forName(prefix+"$CaptureArgs");listenerType=Class.forName(prefix+"$ScreenCaptureListener");
            Class<?> builder=Class.forName(prefix+"$CaptureArgs$Builder");build=builder.getDeclaredMethod("build");
            if(build.getReturnType()!=argsType)throw new NoSuchMethodException("Capture builder result mismatch");
            format=field(builder,"mPixelFormat",int.class);preserve=field(builder,"mPreserveDisplayColors",boolean.class);
            Field mode;try{mode=field(builder,"mMiCaptureMode",int.class);}catch(NoSuchFieldException missing){mode=null;}miMode=mode;
            ratio=field(buffer,"mHdrSdrRatio",float.class);
            factory=buffer.getDeclaredMethod("createFromNative",HardwareBuffer.class,int.class,boolean.class,boolean.class,HardwareBuffer.class,float.class);factory.setAccessible(true);
            asBitmap=buffer.getDeclaredMethod("asBitmap");getHardware=buffer.getDeclaredMethod("getHardwareBuffer");
            secure=buffer.getDeclaredMethod("containsSecureLayers");hdr=buffer.getDeclaredMethod("containsHdrLayers");
            syncListener=captureType.getDeclaredMethod("createSyncCaptureListener");
            wmsCapture=Class.forName("android.view.IWindowManager").getMethod("captureDisplay",int.class,argsType,listenerType);
        }
    }
    private static Field field(Class<?> type,String name,Class<?> expected)throws Exception {
        Field f=type.getDeclaredField(name);if(f.getType()!=expected)throw new NoSuchFieldException(name+" type mismatch");f.setAccessible(true);return f;
    }
    static void installAdapter(Class<?> buffer)throws Exception {
        Adapter a=new Adapter(buffer);
        if(!HookRuntime.deoptimize(a.build)||!HookRuntime.deoptimize(a.asBitmap))throw new IllegalStateException("Capture call path cannot be verified");
        HookRuntime.add(a.factory,new HookRuntime.Callback(){protected void after(HookRuntime.Call call){
            if(!call.hasThrowable()&&call.getResult()!=null)HookObjects.setAdditionalInstanceField(call.getResult(),DATASPACE,call.args[1]);
        }});
        HookRuntime.add(a.build,new HookRuntime.Callback(){
            protected void before(HookRuntime.Call call)throws Throwable {
                Scope s=ACTIVE.get();if(!a.ready||s==null||!s.requested()||s.preparing||s.retrying||s.changed)return;
                if("compat".equals(s.config.hdrCaptureMode)&&a.miMode==null){s.put("reason","此系统没有小米兼容取帧参数，保留原有路径");return;}
                Object builder=call.thisObject;
                int format=a.format.getInt(builder),mode=a.miMode==null?0:a.miMode.getInt(builder);boolean preserve=a.preserve.getBoolean(builder);
                if(format!=1||preserve){s.put("reason","原有请求使用特殊格式或显示匹配，未叠加修改");return;}
                s.preparing=true;
                try{s.originalArgs=a.build.invoke(builder);}finally{s.preparing=false;}
                try {
                    a.format.setInt(builder,HardwareBuffer.RGBA_FP16);a.preserve.setBoolean(builder,true);
                    if("compat".equals(s.config.hdrCaptureMode))a.miMode.setInt(builder,0);
                    call.setObjectExtra("hdr_restore",new Object[]{builder,format,preserve,mode});
                    s.adapter=a;s.changed=true;s.put("request","fp16_display_colors");s.put("miCaptureMode",a.miMode==null?"unavailable":a.miMode.getInt(builder));
                }catch(Throwable failure){
                    a.format.setInt(builder,format);a.preserve.setBoolean(builder,preserve);if(a.miMode!=null)a.miMode.setInt(builder,mode);
                    s.changed=false;s.put("reason",failure.toString());s.put("status","unsupported_native_retained");
                }
            }
            protected void after(HookRuntime.Call call)throws Throwable {
                Object[] restore=(Object[])call.getObjectExtra("hdr_restore");if(restore==null)return;
                a.format.setInt(restore[0],(Integer)restore[1]);a.preserve.setBoolean(restore[0],(Boolean)restore[2]);
                if(a.miMode!=null)a.miMode.setInt(restore[0],(Integer)restore[3]);
            }
        });
        synchronized(ADAPTERS){ADAPTERS.put(buffer,a);}a.ready=true;
    }
    static void begin(Context context,int display) {
        Scope s=new Scope(context,display);ACTIVE.set(s);
        s.put("at",System.currentTimeMillis());s.put("display",display);s.put("mode",s.config.hdrCaptureMode);s.put("rgb",s.config.captureRgb);s.put("status","native");
    }
    static Bitmap process(Object buffer,Bitmap original) {
        Scope s=ACTIVE.get();if(s==null||s.retrying)return original;
        describe(s,original,"system");
        if(!s.changed)return original;
        Adapter a=s.adapter;if(a==null||!a.bufferType.isInstance(buffer))return original;
        try {
            if(Boolean.TRUE.equals(a.secure.invoke(buffer))){s.blocked=true;s.put("status","protected");return original;}
            HardwareBuffer hardware=(HardwareBuffer)a.getHardware.invoke(buffer);
            if(hardware==null||hardware.isClosed())throw new IllegalStateException("高精度缓冲区不可用");
            if((hardware.getUsage()&HardwareBuffer.USAGE_PROTECTED_CONTENT)!=0){s.blocked=true;s.put("status","protected");return original;}
            boolean hdr=Boolean.TRUE.equals(a.hdr.invoke(buffer));float ratio=a.ratio.getFloat(buffer);
            Object data=HookObjects.getAdditionalInstanceField(buffer,DATASPACE);
            s.put("format",hardware.getFormat());s.put("containsHdr",hdr);s.put("nativeRatio",Float.toString(ratio));s.put("dataspace",data);s.put("colorSpace",String.valueOf(original==null?null:original.getColorSpace()));
            if(original!=null&&original.hasGainmap()){
                s.put("status",HdrSignal.inspect(original).hdr?"native_gainmap_retained":"neutral_gainmap_retained");return original;
            }
            if(hardware.getFormat()!=HardwareBuffer.RGBA_FP16||!(data instanceof Integer))throw new IllegalStateException("系统未返回已确认格式与色彩范围的 FP16 画面");
            s.put("preciseReadStarted",true);
            HdrCaptureImage.Result result=HdrCaptureImage.read(original,(Integer)data,ratio,hdr,s.config.captureRgb,failure->{
                s.put("rgbFailure",failure.toString());
                MiuiHook.log("RGB capture rejected; trying normal precise conversion of the same frame: "+failure);
            });
            boolean downgraded=s.config.captureRgb&&!result.rgb();
            s.put("gainMode",result.rgb()?"rgb":"normal");
            s.put("status",result.bitmap().hasGainmap()?(downgraded?"rebuilt_hdr_normal_fallback":"rebuilt_hdr"):
                    (downgraded?"captured_sdr_normal_fallback":"captured_sdr"));
            s.put("width",result.bitmap().getWidth());s.put("height",result.bitmap().getHeight());
            s.put("channelPeaks",new JSONArray(new float[]{result.report()[0],result.report()[1],result.report()[2]}));
            s.put("gainMax",new JSONArray(new float[]{result.report()[3],result.report()[4],result.report()[5]}));
            s.put("highlightPixels",(int)result.report()[6]);s.put("coloredGainPixels",(int)result.report()[7]);s.put("normalization",result.report()[9]);
            describe(s,result.bitmap(),"output");
            return result.bitmap();
        }catch(Throwable failure){
            if(s.report.optBoolean("preciseReadStarted"))s.put("normalFailure",failure.toString());
            s.put("reason",failure.toString());s.put("status","fallback_requested");
            MiuiHook.log("HDR high-precision capture rejected: "+failure);
            return fallback(s,original);
        }
    }
    private static Bitmap fallback(Scope s,Bitmap original) {
        if(s.retrying||s.blocked||s.originalArgs==null)return original;
        Object result=null;s.retrying=true;
        try {
            Adapter a=s.adapter;Object listener=a.syncListener.invoke(null);
            Object service=Class.forName("android.os.ServiceManager").getDeclaredMethod("getService",String.class).invoke(null,"window");
            Object window=Class.forName("android.view.IWindowManager$Stub").getDeclaredMethod("asInterface",android.os.IBinder.class).invoke(null,service);
            a.wmsCapture.invoke(window,s.display,s.originalArgs,listener);
            Method getBuffer=listener.getClass().getDeclaredMethod("getBuffer");getBuffer.setAccessible(true);result=getBuffer.invoke(listener);
            if(result==null)throw new IllegalStateException("原有截图路径未返回画面");
            if(Boolean.TRUE.equals(a.secure.invoke(result))){s.put("status","protected");return null;}
            Bitmap bitmap=(Bitmap)a.asBitmap.invoke(result);
            if(bitmap==null)throw new IllegalStateException("原有截图路径未返回图片");
            s.put("status","native_fallback");describe(s,bitmap,"output");return bitmap;
        }catch(Throwable failure){s.put("fallbackError",failure.toString());s.put("status","failed");return null;}
        finally {
            s.retrying=false;
            if(result!=null)try{HardwareBuffer hardware=(HardwareBuffer)s.adapter.getHardware.invoke(result);if(hardware!=null)hardware.close();}catch(Throwable ignored){}
        }
    }
    private static void describe(Scope scope,Bitmap bitmap,String prefix) {
        if(bitmap==null||bitmap.isRecycled())return;
        try{
            scope.put(prefix+"GainmapPresent",bitmap.hasGainmap());scope.put(prefix+"HdrState",HdrSignal.inspect(bitmap).state);
            if(bitmap.hasGainmap()){
                android.graphics.Gainmap gain=bitmap.getGainmap();Bitmap map=gain.getGainmapContents();
                scope.put(prefix+"GainMin",new JSONArray(gain.getRatioMin()));scope.put(prefix+"GainMax",new JSONArray(gain.getRatioMax()));
                scope.put(prefix+"FullHdrRatio",gain.getDisplayRatioForFullHdr());
                scope.put(prefix+"MapWidth",map.getWidth());scope.put(prefix+"MapHeight",map.getHeight());scope.put(prefix+"MapFormat",String.valueOf(map.getConfig()));
                Bitmap own=null;
                try{
                    if(NativeBridge.loaded&&(long)map.getWidth()*map.getHeight()<=16000000){
                        Bitmap readable=map;
                        if(map.getConfig()==Bitmap.Config.HARDWARE){own=map.copy(Bitmap.Config.ARGB_8888,false);readable=own;}
                        int[] channels=new int[3];
                        if(readable!=null&&NativeBridge.gainmapChannels(readable,channels)){
                            scope.put(prefix+"GainChannels",channels[0]==1?"single_channel":channels[2]==0?"identical_rgb":"distinct_rgb");
                            scope.put(prefix+"DistinctGainPixels",channels[2]);scope.put(prefix+"GainPixelCount",channels[1]);
                        }
                    }
                }finally{if(own!=null)own.recycle();}
            }
        }catch(Throwable failure){scope.put(prefix+"InspectionError",failure.toString());}
    }
    static void finish() {
        Scope s=ACTIVE.get();if(s==null)return;ACTIVE.set(s.previous);
        if(s.requested()&&!s.changed)s.put("status","unsupported_native_retained");
        s.put("elapsedMs",SystemClock.elapsedRealtime()-s.started);
        String report=s.report.toString();MiuiHook.log("HDR_CAPTURE "+report);
        REPORTS.execute(()->{try{Bundle request=new Bundle();request.putString("report",report);s.context.getContentResolver().call(Uri.parse("content://dev.local.miuihdr.options"),"hdr_capture_report",null,request);}catch(Throwable e){MiuiHook.log("HDR capture report unavailable: "+e);}});
    }
}
