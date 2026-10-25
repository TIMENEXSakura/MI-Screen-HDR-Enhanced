package dev.local.miuihdr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
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

/** Keep the bloom in Xiaomi's capture-excluded window until its fade completes.
 * Only editor chrome fades; the HDR image never passes through a dimmed layer. */
final class EditorTransition {
 private static final WeakHashMap<IBinder,Session> SESSIONS=new WeakHashMap<>();
 private static final Handler MAIN=new Handler(Looper.getMainLooper());
 static int fadesStarted,fadesFinished;
 static void remember(IBinder binder,ImageView image){
  if(binder==null||image==null||!ValueAnimator.areAnimatorsEnabled())return;
  HdrFrame frame=HdrFrame.current(image);
  if(frame!=null&&!SESSIONS.containsKey(binder))SESSIONS.put(binder,new Session(binder,image,frame));
 }
 static void prepare(Activity editor,IBinder binder){
  Session session=SESSIONS.get(binder);if(session!=null)session.editor=new WeakReference<>(editor);
 }
 private static Session session(Activity editor){
  for(Session s:new ArrayList<>(SESSIONS.values()))if(s.editor.get()==editor)return s;
  return null;
 }
 static void beforeCapture(){for(Session s:new ArrayList<>(SESSIONS.values()))s.finish();}
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
   protected void after(HookRuntime.Call p){if(!p.hasThrowable()){Session s=session((Activity)p.thisObject);if(s!=null)s.begin();}}
  });
  HookRuntime.add(activity.getDeclaredMethod("onStop"),new HookRuntime.Callback(){
   protected void before(HookRuntime.Call p){Session s=session((Activity)p.thisObject);if(s!=null)s.finish();}
  });
 }
 private static final class Session implements ViewTreeObserver.OnPreDrawListener {
  final WeakReference<IBinder> binder;
  final WeakReference<ImageView> image;
  final HdrFrame frame;
  WeakReference<Activity> editor=new WeakReference<>(null);
  WeakReference<View> decor=new WeakReference<>(null),top=new WeakReference<>(null),bottom=new WeakReference<>(null);
  boolean waiting,finished;
  float fraction;
  ValueAnimator animator;
  Object deferredModel;
  Method deferredDismiss;
  final Runnable timeout=this::finish;
  Session(IBinder key,ImageView view,HdrFrame glow){binder=new WeakReference<>(key);image=new WeakReference<>(view);frame=glow;}
  void begin(){
   if(waiting||finished)return;Activity activity=editor.get();ImageView view=image.get();
   if(activity==null||view==null||HdrFrame.current(view)!=frame)return;
   try{
    View first=(View)EditorPreviewHdr.field(activity,"mTopLayoutContainer");
    View last=(View)EditorPreviewHdr.field(activity,"mBottomLayoutView");
    if(first==null||last==null)return;
    View root=activity.getWindow().getDecorView();
    top=new WeakReference<>(first);bottom=new WeakReference<>(last);decor=new WeakReference<>(root);
    waiting=true;fraction=0;root.getViewTreeObserver().addOnPreDrawListener(this);apply();
    // Native loading failures, cancellation and old implementations must not
    // leave an invisible editor or a retained screenshot window.
    MAIN.postDelayed(timeout,3000);
   }catch(Throwable t){finish();MiuiHook.log("Editor fade kept native animation: "+t);}
  }
  void apply(){View a=top.get(),b=bottom.get();if(a!=null)a.setAlpha(fraction);if(b!=null)b.setAlpha(fraction);}
  public boolean onPreDraw(){if(!finished)apply();return true;}
  boolean deferDismiss(Object model,Method method){
   if(finished||!waiting)return false;
   if(deferredModel!=null)return true;
   Activity activity=editor.get();ImageView view=image.get();
   if(activity==null||activity.isFinishing()||activity.isDestroyed()||view==null||!view.isAttachedToWindow()||HdrFrame.current(view)!=frame){finish();return false;}
   try{
    ValueAnimator fade=ValueAnimator.ofFloat(0,1);fade.setDuration(360);
    fade.setInterpolator(new PathInterpolator(.2f,0,.2f,1));
    fade.addUpdateListener(a->{fraction=(Float)a.getAnimatedValue();frame.setAlpha(Math.round(255*(1-fraction)));apply();});
    fade.addListener(new AnimatorListenerAdapter(){public void onAnimationEnd(Animator a){finish();}});
    animator=fade;deferredModel=model;deferredDismiss=method;
    fade.start();fadesStarted++;MiuiHook.log("Editor bloom/chrome crossfade started duration=360ms");return true;
   }catch(Throwable t){
    // The current hook will run the native dismiss itself on this path.
    deferredModel=null;deferredDismiss=null;finish();MiuiHook.log("Editor fade unavailable: "+t);return false;
   }
  }
  void finish(){
   if(finished)return;finished=true;MAIN.removeCallbacks(timeout);
   ValueAnimator fade=animator;animator=null;
   if(fade!=null){fade.removeAllListeners();fade.removeAllUpdateListeners();fade.cancel();}
   View root=decor.get();if(root!=null&&root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnPreDrawListener(this);
   if(waiting){fraction=1;apply();}
   Object model=deferredModel;Method method=deferredDismiss;deferredModel=null;deferredDismiss=null;
   IBinder key=binder.get();if(SESSIONS.get(key)==this)SESSIONS.remove(key);
   if(model!=null){
    frame.setAlpha(0);
    try{method.invoke(model);fadesFinished++;MiuiHook.log("Editor crossfade finished; native thumbnail dismissed");}
    catch(Throwable t){MiuiHook.log("Native thumbnail dismiss failed after fade: "+t);}
   }
  }
 }
}
