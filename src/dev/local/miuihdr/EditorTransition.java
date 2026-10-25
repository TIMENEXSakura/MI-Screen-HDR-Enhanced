package dev.local.miuihdr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Fade only Xiaomi's crop-frame drawable; never fade the HDR image or window.
 * The bloom stays in the capture-excluded screenshot window during the handoff. */
final class EditorTransition {
 private static final WeakHashMap<IBinder,Session> SESSIONS=new WeakHashMap<>();
 private static final Handler MAIN=new Handler(Looper.getMainLooper());
 private static boolean cropHookReady;
 static int fadesStarted,fadesFinished,cropFrameDraws,cropFramePartialDraws;
 static String lastEvent="none";
 static void remember(IBinder binder,ImageView image){
  if(!cropHookReady||binder==null||image==null||!ValueAnimator.areAnimatorsEnabled())return;
  HdrFrame frame=HdrFrame.current(image);
  if(frame!=null&&!SESSIONS.containsKey(binder)){SESSIONS.put(binder,new Session(binder,image,frame));lastEvent="thumbnail remembered";}
 }
 static void prepare(Activity editor,IBinder binder){Session s=SESSIONS.get(binder);if(s!=null)s.prepare(editor);}
 private static Session session(Activity editor){
  if(editor==null)return null;
  for(Session s:new ArrayList<>(SESSIONS.values()))if(s.editor.get()==editor)return s;
  return null;
 }
 private static Activity activity(View view){
  Context context=view.getContext();
  for(int depth=0;depth<16&&context!=null;depth++){
   if(context instanceof Activity)return (Activity)context;
   if(!(context instanceof ContextWrapper))break;
   Context next=((ContextWrapper)context).getBaseContext();if(next==context)break;context=next;
  }
  return null;
 }
 static void beforeCapture(){for(Session s:new ArrayList<>(SESSIONS.values()))s.finish("capture cleanup");}
 static void install(ClassLoader loader)throws Exception{
  Class<?> crop=loader.loadClass("com.miui.gallery.editor.photo.screen.crop.ScreenCropView");
  HookRuntime.add(crop.getDeclaredMethod("drawOverlay",Canvas.class),new HookRuntime.Callback(){
   protected void before(HookRuntime.Call p)throws Exception{
    if(SESSIONS.isEmpty()||Looper.myLooper()!=Looper.getMainLooper())return;
    View view=(View)EditorPreviewHdr.field(p.thisObject,"mEditorView");
    Session s=session(activity(view));if(s==null||!s.prepared||s.finished)return;
    Drawable frame=(Drawable)EditorPreviewHdr.field(p.thisObject,"mCropWindow");
    CropFrameAlpha alpha=CropFrameAlpha.begin(frame,s.fraction);
    p.setObjectExtra("crop_frame_alpha",alpha);s.cropViews.put(view,Boolean.TRUE);
    cropFrameDraws++;if(s.fraction>0&&s.fraction<1)cropFramePartialDraws++;
   }
   protected void after(HookRuntime.Call p){
    CropFrameAlpha alpha=(CropFrameAlpha)p.getObjectExtra("crop_frame_alpha");
    if(alpha!=null)alpha.close();
   }
  });
  Class<?> model=loader.loadClass("com.miui.gallery.editor.transition.TransitionModel");
  Method dismiss=model.getDeclaredMethod("dismiss");dismiss.setAccessible(true);
  HookRuntime.add(dismiss,new HookRuntime.Callback(){protected void before(HookRuntime.Call p){
   if(Looper.myLooper()!=Looper.getMainLooper())return;
   Session s=SESSIONS.get((IBinder)p.thisObject);
   if(s!=null&&s.deferDismiss(p.thisObject,dismiss))p.setResult(null);
  }});
  Class<?> activity=loader.loadClass("com.miui.gallery.editor.photo.screen.home.ScreenEditorActivity");
  HookRuntime.add(activity.getDeclaredMethod("onTransitionStart",long.class),new HookRuntime.Callback(){
   protected void after(HookRuntime.Call p){if(!p.hasThrowable()){Session s=session((Activity)p.thisObject);if(s!=null){s.started=true;lastEvent="native enlargement started";}}}
  });
  HookRuntime.add(activity.getDeclaredMethod("onStop"),new HookRuntime.Callback(){
   protected void before(HookRuntime.Call p){Session s=session((Activity)p.thisObject);if(s!=null)s.finish("activity stopped");}
  });
  cropHookReady=true;
 }
 private static final class Session {
  final WeakReference<IBinder> binder;
  final WeakReference<ImageView> image;
  final HdrFrame frame;
  WeakReference<Activity> editor=new WeakReference<>(null);
  final WeakHashMap<View,Boolean> cropViews=new WeakHashMap<>();
  boolean prepared,started,finished;
  float fraction;
  int oldImageAlpha=-1;
  ValueAnimator animator;
  ViewTreeObserver commitObserver;
  Runnable frameCommit;
  Object deferredModel;
  Method deferredDismiss;
  final Runnable timeout=()->finish("native handoff timeout");
  Session(IBinder key,ImageView view,HdrFrame glow){binder=new WeakReference<>(key);image=new WeakReference<>(view);frame=glow;}
  void prepare(Activity activity){
   if(prepared||finished)return;
   editor=new WeakReference<>(activity);fraction=0;prepared=true;
   MAIN.postDelayed(timeout,3000);lastEvent="crop frame awaiting image handoff";
  }
  void apply(){
   for(View view:new ArrayList<>(cropViews.keySet()))if(view!=null)view.postInvalidateOnAnimation();
  }
  boolean deferDismiss(Object model,Method method){
   if(finished)return false;
   if(!prepared||!started){finish("native animation skipped");return false;}
   if(deferredModel!=null)return true;
   Activity activity=editor.get();ImageView view=image.get();
   if(activity==null||activity.isFinishing()||activity.isDestroyed()||view==null||!view.isAttachedToWindow()||HdrFrame.current(view)!=frame){finish("thumbnail detached");return false;}
   try{
    View root=activity.getWindow().getDecorView();
    if(!root.isHardwareAccelerated()||!root.getViewTreeObserver().isAlive()){finish("editor frame unavailable");return false;}
    deferredModel=model;deferredDismiss=method;
    MAIN.removeCallbacks(timeout);MAIN.postDelayed(timeout,1200);
    // Binding a bitmap and posting closeWindow does not guarantee that its HDR
    // frame is ready. Keep the original thumbnail until the editor submits it.
    commitObserver=root.getViewTreeObserver();
    frameCommit=()->MAIN.post(()->{frameCommit=null;commitObserver=null;if(!finished)startFade();});
    commitObserver.registerFrameCommitCallback(frameCommit);
    apply();root.postInvalidateOnAnimation();lastEvent="awaiting editor frame commit";
    return true;
   }catch(Throwable t){
    boolean deferred=deferredModel!=null;
    finish("frame handoff unavailable");MiuiHook.log("Crop frame handoff unavailable: "+t);return deferred;
   }
  }
  void startFade(){
   ImageView view=image.get();Activity activity=editor.get();
   if(activity==null||activity.isFinishing()||activity.isDestroyed()||view==null||!view.isAttachedToWindow()||HdrFrame.current(view)!=frame){finish("thumbnail detached before fade");return;}
   try{
    ValueAnimator fade=ValueAnimator.ofFloat(0,1);fade.setDuration(360);
    fade.setInterpolator(new PathInterpolator(.2f,0,.2f,1));
    fade.addUpdateListener(a->{fraction=(Float)a.getAnimatedValue();frame.setAlpha(Math.round(255*(1-fraction)));apply();});
    fade.addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){finish("fade complete");}});
    animator=fade;
    MAIN.removeCallbacks(timeout);MAIN.postDelayed(timeout,1200);
    // Native dismiss is requested only after the editor bitmap and enlargement
    // are ready. Reveal that HDR image now, otherwise the opaque thumbnail would
    // cover the crop frame until it has already reached full opacity. Image alpha
    // does not change the View geometry/alpha used by the separate bloom overlay.
    oldImageAlpha=view.getImageAlpha();view.setImageAlpha(0);
    fade.start();fadesStarted++;lastEvent="crop frame fading in; bloom fading out";
    MiuiHook.log("Crop frame/bloom crossfade started duration=360ms; editor frame committed; window unchanged");
   }catch(Throwable t){
    finish("fade unavailable");MiuiHook.log("Crop frame fade unavailable: "+t);
   }
  }
  void finish(String reason){
   if(finished)return;finished=true;MAIN.removeCallbacks(timeout);
   if(commitObserver!=null&&frameCommit!=null&&commitObserver.isAlive())commitObserver.unregisterFrameCommitCallback(frameCommit);
   frameCommit=null;commitObserver=null;
   ValueAnimator fade=animator;animator=null;
   if(fade!=null){fade.removeAllListeners();fade.removeAllUpdateListeners();fade.cancel();}
   if(prepared){fraction=1;apply();}
   Object model=deferredModel;Method method=deferredDismiss;deferredModel=null;deferredDismiss=null;
   IBinder key=binder.get();if(SESSIONS.get(key)==this)SESSIONS.remove(key);
   boolean dismissed=model==null;
   if(model!=null){
    frame.setAlpha(0);
    try{method.invoke(model);dismissed=true;fadesFinished++;MiuiHook.log("Editor crossfade finished; native thumbnail dismissed");}
    catch(Throwable t){MiuiHook.log("Native thumbnail dismiss failed after fade: "+t);}
   }
   ImageView view=image.get();if(view!=null&&oldImageAlpha>=0){
    if(dismissed||!view.isAttachedToWindow()||view.getAlpha()==0)view.setImageAlpha(oldImageAlpha);
    else{
     // Do not cover the ready editor again if native dismiss failed. Restore the
     // reusable ImageView when Xiaomi's own fallback removes its window.
     final int original=oldImageAlpha;
     view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
      public void onViewAttachedToWindow(View v){}
      public void onViewDetachedFromWindow(View v){((ImageView)v).setImageAlpha(original);v.removeOnAttachStateChangeListener(this);}
     });
    }
   }
   cropViews.clear();
   lastEvent=reason;
  }
 }
}
