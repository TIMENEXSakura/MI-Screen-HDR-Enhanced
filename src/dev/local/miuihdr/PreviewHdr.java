package dev.local.miuihdr;

import android.content.pm.ActivityInfo;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;
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
 private static final WeakHashMap<ImageView,PreviewHdr> ACTIVE=new WeakHashMap<>();
 private static final WeakHashMap<View,PreviewHdr> WINDOWS=new WeakHashMap<>();
 private final WeakReference<ImageView> host;
 private WeakReference<View> root=new WeakReference<>(null);
 private final float ratio;
 private final int oldLayer;
 private int oldMode;
 private float oldHeadroom;
 private boolean changed;

 private PreviewHdr(ImageView view,float ratio){
  host=new WeakReference<>(view);this.ratio=ratio;oldLayer=view.getLayerType();
 }
 static void bind(ImageView view,HdrSignal.Info info){
  clear(view);
  if(info==null||!info.hdr||!Float.isFinite(info.ratio)||info.ratio<=1)return;
  PreviewHdr state=new PreviewHdr(view,info.ratio);
  ACTIVE.put(view,state);view.addOnAttachStateChangeListener(state);
  // LAYER_TYPE_NONE still uses hardware-accelerated drawing; it only removes
  // the intermediate bitmap cache selected by Xiaomi's entry animation.
  view.setLayerType(View.LAYER_TYPE_NONE,null);
  if(view.isAttachedToWindow())state.enable(view.getRootView());
 }
 static boolean directDraw(View view){return view instanceof ImageView&&ACTIVE.containsKey(view);}
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
 static void clear(ImageView view){
  PreviewHdr state=ACTIVE.remove(view);if(state==null)return;
  view.removeOnAttachStateChangeListener(state);state.restore();
  view.setLayerType(state.oldLayer,null);
 }
 static void clearAll(){for(ImageView view:new ArrayList<>(ACTIVE.keySet()))if(view!=null)clear(view);}
 private void enable(View view){
  if(changed||!(view.getLayoutParams() instanceof WindowManager.LayoutParams))return;
  try{
   WindowManager.LayoutParams current=(WindowManager.LayoutParams)view.getLayoutParams();
   oldMode=current.getColorMode();
   if(Build.VERSION.SDK_INT>=35)oldHeadroom=current.getDesiredHdrHeadroom();
   WindowManager.LayoutParams next=new WindowManager.LayoutParams();next.copyFrom(current);
   next.setColorMode(ActivityInfo.COLOR_MODE_HDR);
   if(Build.VERSION.SDK_INT>=35)next.setDesiredHdrHeadroom(ratio);
   root=new WeakReference<>(view);WINDOWS.put(view,this);
   ((WindowManager)view.getContext().getSystemService("window")).updateViewLayout(view,next);
   changed=true;view.invalidate();
   HdrLog.log("HDR image preview enabled headroom="+ratio+" directDraw=true");
  }catch(Throwable t){WINDOWS.remove(view);HdrLog.log("HDR preview window unavailable; original display retained: "+t);}
 }
 private void restore(){
  View view=root.get();if(view!=null)WINDOWS.remove(view);if(!changed||view==null)return;
  try{
   if(view.getLayoutParams() instanceof WindowManager.LayoutParams){
    WindowManager.LayoutParams next=new WindowManager.LayoutParams();next.copyFrom((WindowManager.LayoutParams)view.getLayoutParams());
    next.setColorMode(oldMode);if(Build.VERSION.SDK_INT>=35)next.setDesiredHdrHeadroom(oldHeadroom);
    if(view.isAttachedToWindow())((WindowManager)view.getContext().getSystemService("window")).updateViewLayout(view,next);
    else view.setLayoutParams(next);
   }
  }catch(Throwable t){HdrLog.log("HDR preview window restore skipped: "+t);}
  changed=false;
 }
 public void onViewAttachedToWindow(View view){enable(view.getRootView());}
 public void onViewDetachedFromWindow(View view){if(view instanceof ImageView)clear((ImageView)view);}
}
