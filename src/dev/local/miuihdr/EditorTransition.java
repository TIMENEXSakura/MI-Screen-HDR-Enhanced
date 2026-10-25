package dev.local.miuihdr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Fade the actual editor window, including its background, from its first frame.
 * Xiaomi's original HDR picture stays opaque above it until the new window is
 * fully visible. The bloom stays in the capture-excluded screenshot window. */
final class EditorTransition {
 private static final WeakHashMap<IBinder,Session> SESSIONS=new WeakHashMap<>();
 private static final WeakHashMap<View,Session> WINDOWS=new WeakHashMap<>();
 private static final Handler MAIN=new Handler(Looper.getMainLooper());
 static int fadesStarted,fadesFinished;
 static String lastEvent="none";
 static void remember(IBinder binder,ImageView image){
  if(binder==null||image==null||!ValueAnimator.areAnimatorsEnabled())return;
  HdrFrame frame=HdrFrame.current(image);
  if(frame!=null&&!SESSIONS.containsKey(binder)){SESSIONS.put(binder,new Session(binder,image,frame));lastEvent="thumbnail remembered";}
 }
 static void prepare(Activity editor,IBinder binder){Session s=SESSIONS.get(binder);if(s!=null)s.prepare(editor);}
 private static Session session(Activity editor){
  for(Session s:new ArrayList<>(SESSIONS.values()))if(s.editor.get()==editor)return s;
  return null;
 }
 static android.view.ViewGroup.LayoutParams keepAlpha(View root,android.view.ViewGroup.LayoutParams params){
  Session s=WINDOWS.get(root);
  if(s==null||s.finished||!(params instanceof WindowManager.LayoutParams))return params;
  WindowManager.LayoutParams next=new WindowManager.LayoutParams();next.copyFrom((WindowManager.LayoutParams)params);
  next.alpha=s.oldAlpha*s.fraction;return next;
 }
 static void beforeCapture(){for(Session s:new ArrayList<>(SESSIONS.values()))s.finish("capture cleanup");}
 static void install(ClassLoader loader)throws Exception{
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
 }
 private static final class Session {
  final WeakReference<IBinder> binder;
  final WeakReference<ImageView> image;
  final HdrFrame frame;
  WeakReference<Activity> editor=new WeakReference<>(null);
  WeakReference<View> decor=new WeakReference<>(null);
  boolean prepared,started,finished;
  float fraction,oldAlpha=1;
  ValueAnimator animator;
  Object deferredModel;
  Method deferredDismiss;
  final Runnable timeout=()->finish("native handoff timeout");
  Session(IBinder key,ImageView view,HdrFrame glow){binder=new WeakReference<>(key);image=new WeakReference<>(view);frame=glow;}
  void prepare(Activity activity){
   if(prepared||finished)return;
   editor=new WeakReference<>(activity);Window window=activity.getWindow();View root=window.getDecorView();
   oldAlpha=window.getAttributes().alpha;decor=new WeakReference<>(root);fraction=0;prepared=true;
   WINDOWS.put(root,this);apply();MAIN.postDelayed(timeout,3000);lastEvent="editor hidden before attachment";
  }
  void apply(){
   Activity activity=editor.get();if(activity==null)return;
   Window window=activity.getWindow();WindowManager.LayoutParams next=new WindowManager.LayoutParams();next.copyFrom(window.getAttributes());
   next.alpha=oldAlpha*fraction;window.setAttributes(next);
  }
  boolean deferDismiss(Object model,Method method){
   if(finished)return false;
   if(!prepared||!started){finish("native animation skipped");return false;}
   if(deferredModel!=null)return true;
   Activity activity=editor.get();ImageView view=image.get();
   if(activity==null||activity.isFinishing()||activity.isDestroyed()||view==null||!view.isAttachedToWindow()||HdrFrame.current(view)!=frame){finish("thumbnail detached");return false;}
   try{
    ValueAnimator fade=ValueAnimator.ofFloat(0,1);fade.setDuration(360);
    fade.setInterpolator(new PathInterpolator(.2f,0,.2f,1));
    fade.addUpdateListener(a->{fraction=(Float)a.getAnimatedValue();frame.setAlpha(Math.round(255*(1-fraction)));apply();});
    fade.addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){finish("fade complete");}});
    animator=fade;deferredModel=model;deferredDismiss=method;
    MAIN.removeCallbacks(timeout);MAIN.postDelayed(timeout,1200);
    fade.start();fadesStarted++;lastEvent="whole editor window fading in";
    MiuiHook.log("Editor window/bloom crossfade started duration=360ms");return true;
   }catch(Throwable t){
    deferredModel=null;deferredDismiss=null;finish("fade unavailable");MiuiHook.log("Editor fade unavailable: "+t);return false;
   }
  }
  void finish(String reason){
   if(finished)return;finished=true;MAIN.removeCallbacks(timeout);
   ValueAnimator fade=animator;animator=null;
   if(fade!=null){fade.removeAllListeners();fade.removeAllUpdateListeners();fade.cancel();}
   View root=decor.get();if(WINDOWS.get(root)==this)WINDOWS.remove(root);
   if(prepared){fraction=1;apply();}
   Object model=deferredModel;Method method=deferredDismiss;deferredModel=null;deferredDismiss=null;
   IBinder key=binder.get();if(SESSIONS.get(key)==this)SESSIONS.remove(key);
   if(model!=null){
    frame.setAlpha(0);
    try{method.invoke(model);fadesFinished++;MiuiHook.log("Editor crossfade finished; native thumbnail dismissed");}
    catch(Throwable t){MiuiHook.log("Native thumbnail dismiss failed after fade: "+t);}
   }
   lastEvent=reason;
  }
 }
}
