package dev.local.miuihdr;

import android.content.pm.ActivityInfo;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
import android.view.Window;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** HDR belongs to the captured image, independently of the decorative bloom.
 * Keep Xiaomi's original bitmap, gainmap, rounded drawable and animations.
 * Avoid a cached View layer between that drawable and the HDR window.
 * All lifecycle methods run on the screenshot UI thread.
 */
final class PreviewHdr implements View.OnAttachStateChangeListener {
 private static final WeakHashMap<View,PreviewHdr> ACTIVE=new WeakHashMap<>();
 private static final WeakHashMap<View,PreviewHdr> WINDOWS=new WeakHashMap<>();
 private static final WeakHashMap<View,WindowManager.LayoutParams> PREPARED=new WeakHashMap<>();
 private final WeakReference<View> host;
 private WeakReference<View> root=new WeakReference<>(null);
 private WeakReference<Window> activityWindow=new WeakReference<>(null);
 private final float ratio;
 private final boolean persistent;
 private final int oldLayer;
 private int oldMode;
 private float oldHeadroom;
 private boolean changed;

 private PreviewHdr(View view,float ratio,boolean persistent){
  host=new WeakReference<>(view);this.ratio=ratio;this.persistent=persistent;oldLayer=view.getLayerType();
 }
 static void bind(ImageView view,HdrSignal.Info info){bind(view,info,false);}
 static void bindEditor(View view,HdrSignal.Info info){bind(view,info,true);}
 static WindowManager.LayoutParams prepareEditor(View decor,WindowManager.LayoutParams original,HdrSignal.Info info){
  if(info==null||!info.hdr||!Float.isFinite(info.ratio)||info.ratio<=1)return original;
  if(!PREPARED.containsKey(decor)){WindowManager.LayoutParams baseline=new WindowManager.LayoutParams();baseline.copyFrom(original);PREPARED.put(decor,baseline);}
  WindowManager.LayoutParams next=new WindowManager.LayoutParams();next.copyFrom(original);
  next.setColorMode(ActivityInfo.COLOR_MODE_HDR);if(Build.VERSION.SDK_INT>=35)next.setDesiredHdrHeadroom(info.ratio);
  return next;
 }
 static void prepareEditor(Window window,HdrSignal.Info info){
  WindowManager.LayoutParams next=prepareEditor(window.getDecorView(),window.getAttributes(),info);
  if(next!=window.getAttributes())window.setAttributes(next);
 }
 private static Window owner(View view){
  Context context=view.getContext();
  for(int i=0;i<12&&context!=null;i++){
   if(context instanceof Activity)return ((Activity)context).getWindow();
   if(!(context instanceof ContextWrapper))break;
   Context base=((ContextWrapper)context).getBaseContext();if(base==context)break;context=base;
  }return null;
 }
 private static void bind(View view,HdrSignal.Info info,boolean persistent){
  PreviewHdr current=ACTIVE.get(view);
  if(current!=null&&info!=null&&info.hdr&&current.ratio==info.ratio&&current.persistent==persistent){view.invalidate();return;}
  clear(view);
  if(info==null||!info.hdr||!Float.isFinite(info.ratio)||info.ratio<=1){
   WindowManager.LayoutParams prepared=PREPARED.remove(view.getRootView());
   if(prepared!=null){Window window=owner(view);if(window!=null){WindowManager.LayoutParams next=new WindowManager.LayoutParams();next.copyFrom(window.getAttributes());next.setColorMode(prepared.getColorMode());if(Build.VERSION.SDK_INT>=35)next.setDesiredHdrHeadroom(prepared.getDesiredHdrHeadroom());window.setAttributes(next);}}
   return;
  }
  PreviewHdr state=new PreviewHdr(view,info.ratio,persistent);
  ACTIVE.put(view,state);view.addOnAttachStateChangeListener(state);
  // LAYER_TYPE_NONE still uses hardware-accelerated drawing; it only removes
  // the intermediate bitmap cache selected by Xiaomi's entry animation.
  view.setLayerType(View.LAYER_TYPE_NONE,null);
  if(view.isAttachedToWindow())state.enable(view.getRootView());
 }
 static boolean directDraw(View view){return ACTIVE.containsKey(view);}
 static android.view.ViewGroup.LayoutParams keepHdr(View view,android.view.ViewGroup.LayoutParams params){
  PreviewHdr state=WINDOWS.get(view);
  if(state==null||!(params instanceof WindowManager.LayoutParams))return params;
  // Xiaomi reuses its own LayoutParams when changing the touch region. Modify
  // a copy so that subsequent SDR captures never inherit our HDR override.
  WindowManager.LayoutParams next=new WindowManager.LayoutParams();next.copyFrom((WindowManager.LayoutParams)params);
  next.setColorMode(ActivityInfo.COLOR_MODE_HDR);
  if(Build.VERSION.SDK_INT>=35)next.setDesiredHdrHeadroom(state.ratio);
  return next;
 }
 static void clear(View view){
  PreviewHdr state=ACTIVE.remove(view);if(state==null)return;
  view.removeOnAttachStateChangeListener(state);state.restore();
  view.setLayerType(state.oldLayer,null);
 }
 static void clearAll(){for(View view:new ArrayList<>(ACTIVE.keySet()))if(view!=null)clear(view);}
 static void clearTransient(){for(View view:new ArrayList<>(ACTIVE.keySet())){PreviewHdr state=ACTIVE.get(view);if(view!=null&&state!=null&&!state.persistent)clear(view);}}
 private void enable(View view){
  if(changed||!(view.getLayoutParams() instanceof WindowManager.LayoutParams))return;
  try{
   WindowManager.LayoutParams current=(WindowManager.LayoutParams)view.getLayoutParams();
   WindowManager.LayoutParams baseline=PREPARED.remove(view);if(baseline==null)baseline=current;
   oldMode=baseline.getColorMode();
   if(Build.VERSION.SDK_INT>=35)oldHeadroom=baseline.getDesiredHdrHeadroom();
   WindowManager.LayoutParams next=new WindowManager.LayoutParams();next.copyFrom(current);
   next.setColorMode(ActivityInfo.COLOR_MODE_HDR);
   if(Build.VERSION.SDK_INT>=35)next.setDesiredHdrHeadroom(ratio);
   root=new WeakReference<>(view);WINDOWS.put(view,this);
   View picture=host.get();Window window=persistent&&picture!=null?owner(picture):null;
   if(window!=null){activityWindow=new WeakReference<>(window);window.setAttributes(next);}
   else ((WindowManager)view.getContext().getSystemService("window")).updateViewLayout(view,next);
   changed=true;view.invalidate();
   MiuiHook.log("HDR image preview enabled headroom="+ratio+" directDraw=true");
  }catch(Throwable t){WINDOWS.remove(view);MiuiHook.log("HDR preview window unavailable; original display retained: "+t);}
 }
 private void restore(){
  View view=root.get();if(view!=null)WINDOWS.remove(view);if(!changed||view==null)return;
  try{
   if(view.getLayoutParams() instanceof WindowManager.LayoutParams){
    WindowManager.LayoutParams next=new WindowManager.LayoutParams();next.copyFrom((WindowManager.LayoutParams)view.getLayoutParams());
    next.setColorMode(oldMode);if(Build.VERSION.SDK_INT>=35)next.setDesiredHdrHeadroom(oldHeadroom);
    Window window=activityWindow.get();
    if(window!=null)window.setAttributes(next);
    else if(view.isAttachedToWindow())((WindowManager)view.getContext().getSystemService("window")).updateViewLayout(view,next);
    else view.setLayoutParams(next);
   }
  }catch(Throwable t){MiuiHook.log("HDR preview window restore skipped: "+t);}
  changed=false;
 }
 public void onViewAttachedToWindow(View view){enable(view.getRootView());}
 public void onViewDetachedFromWindow(View view){clear(view);}
}
