package dev.local.miuihdr;
import dev.local.miuihdr.HookRuntime.Callback;
import dev.local.miuihdr.HookRuntime.Call;
import android.app.Service;
import android.content.Intent;
import android.os.*;
final class NativeProbeEntry {
 static final String ACTION="dev.local.miuihdr.ROOT_CAPTURE_TEST";
 static final String STATUS="dev.local.miuihdr.ROOT_HOOK_STATUS";
 static void install()throws Exception{
  HookRuntime.add(Service.class.getDeclaredMethod("onStartCommand",Intent.class,int.class,int.class),new Callback(){
   protected void after(Call p){
    final Service service=(Service)p.thisObject;Intent intent=(Intent)p.args[0];
    // The original service remains non-exported. Only root/system can start this entry.
    if(!service.getClass().getName().equals("com.miui.screenshot.TakeScreenshotService")||intent==null)return;
    if(STATUS.equals(intent.getAction())){
     try{java.nio.file.Files.writeString(new java.io.File(service.getCacheDir(),"mi-screen-hdr-status.txt").toPath(),
      "version=1.5.2-hdrfix.2\napi="+HookRuntime.apiVersion()+"\nhooks="+HookRuntime.count()+"\nnativeLoaded="+NativeBridge.loaded+"\nnativeGate="+NativeBridge.gate+"\neditorPreview="+EditorPreviewHdr.lastPreview+"\nearlyHdrTransitions="+EditorPreviewHdr.earlyTransitions+"\neditorFadesStarted="+EditorTransition.fadesStarted+"\neditorFadesFinished="+EditorTransition.fadesFinished+"\ncropFrameDraws="+EditorTransition.cropFrameDraws+"\ncropFramePartialDraws="+EditorTransition.cropFramePartialDraws+"\neditorFadeState="+EditorTransition.lastEvent+"\n");}
     catch(Throwable t){MiuiHook.log("Status report failed: "+t);}
     return;
    }
    if(!ACTION.equals(intent.getAction()))return;
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
