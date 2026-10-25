package dev.local.miuihdr;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import io.github.libxposed.api.XposedInterface;
final class NativeProbeEntry {
 static final String ACTION="dev.local.miuihdr.ROOT_CAPTURE_TEST";
 static void install(XposedInterface api)throws Exception{
  api.hook(Service.class.getDeclaredMethod("onStartCommand",Intent.class,int.class,int.class)).setId("probe.capture").intercept(chain -> {
   Object result=chain.proceed();
   final Service service=(Service)chain.getThisObject();Intent intent=(Intent)chain.getArg(0);
   // The original service remains non-exported. Only root/system can start this entry.
   if(!service.getClass().getName().equals("com.miui.screenshot.TakeScreenshotService")||intent==null||!ACTION.equals(intent.getAction()))return result;
   new Handler(Looper.getMainLooper()).post(new Runnable(){public void run(){try{
    IBinder binder=service.onBind(new Intent());Message request=Message.obtain();request.what=1;
    request.replyTo=new Messenger(new Handler(Looper.getMainLooper()){
     @Override public void handleMessage(Message response){HdrLog.log("Root native probe callback what="+response.what+" result="+response.obj);}
    });
    new Messenger(binder).send(request);HdrLog.log("Root test entered native screenshot Messenger");
    new Handler(Looper.getMainLooper()).postDelayed(new Runnable(){public void run(){service.stopSelf();}},6500);
   }catch(Throwable t){HdrLog.log("Root native probe failed: "+t);service.stopSelf();}}});
   return result;
  });
 }
}
