package dev.local.miuihdr;
import android.app.Service;
import android.content.Intent;
import android.os.*;
import de.robv.android.xposed.*;
final class NativeProbeEntry {
 static final String ACTION="dev.local.miuihdr.ROOT_CAPTURE_TEST";
 static void install()throws Exception{
  XposedBridge.hookMethod(Service.class.getDeclaredMethod("onStartCommand",Intent.class,int.class,int.class),new XC_MethodHook(){
   protected void afterHookedMethod(MethodHookParam p){
    final Service service=(Service)p.thisObject;Intent intent=(Intent)p.args[0];
    // The original service remains non-exported. Only root/system can start this entry.
    if(!service.getClass().getName().equals("com.miui.screenshot.TakeScreenshotService")||intent==null||!ACTION.equals(intent.getAction()))return;
    new Handler(Looper.getMainLooper()).post(new Runnable(){public void run(){try{
     IBinder binder=service.onBind(new Intent());Message request=Message.obtain();request.what=1;
     request.replyTo=new Messenger(new Handler(Looper.getMainLooper()){
      @Override public void handleMessage(Message response){MiuiHook.log("Root native probe callback what="+response.what+" result="+response.obj);}
     });
     new Messenger(binder).send(request);MiuiHook.log("Root test entered native screenshot Messenger");
     new Handler(Looper.getMainLooper()).postDelayed(new Runnable(){public void run(){service.stopSelf();}},6500);
    }catch(Throwable t){MiuiHook.log("Root native probe failed: "+t);service.stopSelf();}}});
   }
  });
 }
}
