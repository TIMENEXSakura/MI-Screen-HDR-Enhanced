package dev.local.miuihdr;
import android.animation.ValueAnimator;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.*;
import android.view.*;
import android.widget.ImageView;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
final class HdrFrame extends Drawable implements View.OnAttachStateChangeListener {
 private static final WeakHashMap<ImageView,HdrFrame> FRAMES=new WeakHashMap<ImageView,HdrFrame>();
 private final WeakReference<ImageView> host;
 private final WeakReference<View> root;
 private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
 private final RectF rect=new RectF(),destination=new RectF();private final Matrix transform=new Matrix();
 private final CharSequence oldDescription;
 private final float density,ratio;private final boolean animate,glow,hdrGlow,badge;
 private Bitmap colorWheel;private long pulseStart;private int opacity=255;
 private final RectF wheelBounds=new RectF();
 private final Paint maskPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
 private final RuntimeShader mask=new RuntimeShader("uniform float4 edge; uniform float radius; uniform float density; uniform float opacity; half4 main(float2 p) { float2 center=(edge.xy+edge.zw)*0.5; float2 extent=(edge.zw-edge.xy)*0.5; float r=min(radius,min(extent.x,extent.y)); float2 q=abs(p-center)-(extent-float2(r)); float d=length(max(q,float2(0)))+min(max(q.x,q.y),0.0)-r; float u=max(d,0.0)/density; float core=1.0-smoothstep(0.3,1.9,u); float halo=0.40*exp(-u*u/98.0)+0.48*exp(-u*u/12.5); float a=clamp(max(core,halo),0.0,1.0)*smoothstep(-0.7,0.7,d+0.35*density)*opacity; return half4(a); }");
 private final long created=SystemClock.uptimeMillis();private int oldMode;private float oldHeadroom;private boolean changedWindow;
 private HdrFrame(ImageView v,HookConfig c,float r){host=new WeakReference<ImageView>(v);root=new WeakReference<View>(v.getRootView());density=v.getResources().getDisplayMetrics().density;ratio=r;oldDescription=v.getContentDescription();animate=ValueAnimator.areAnimatorsEnabled();glow=c.glow;hdrGlow=c.hdrGlow;badge=c.badge;paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));}
 static void clear(ImageView view){HdrFrame old=FRAMES.remove(view);if(old==null)return;View r=old.root.get();if(r!=null){r.getOverlay().remove(old);old.restoreWindow(r);r.invalidate();}view.removeOnAttachStateChangeListener(old);if("HDR 截图预览".contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))view.setContentDescription(old.oldDescription);
  // RenderThread may still reference the previous display list: let bitmap lifetime follow GC.
  old.colorWheel=null;
 }
 static void attach(ImageView v,HookConfig config,float ratio){clear(v);HdrFrame f=new HdrFrame(v,config,ratio);FRAMES.put(v,f);v.addOnAttachStateChangeListener(f);v.setContentDescription("HDR 截图预览");View r=v.getRootView();f.setBounds(0,0,r.getWidth(),r.getHeight());f.enableWindow(r);r.getOverlay().add(f);r.invalidate();MiuiHook.log("HDR bloom attached glow="+config.glow+" hdrGlow="+config.hdrGlow+" badge="+config.badge);}
 private void enableWindow(View r){if(changedWindow||!hdrGlow||!glow)return;try{
  if(r.getLayoutParams() instanceof WindowManager.LayoutParams){WindowManager.LayoutParams p=(WindowManager.LayoutParams)r.getLayoutParams();oldMode=p.getColorMode();oldHeadroom=p.getDesiredHdrHeadroom();p.setColorMode(2);p.setDesiredHdrHeadroom(Math.max(1f,ratio));((WindowManager)r.getContext().getSystemService("window")).updateViewLayout(r,p);changedWindow=true;MiuiHook.log("HDR bloom window mode="+p.getColorMode()+" headroom="+p.getDesiredHdrHeadroom());}
 }catch(Throwable t){MiuiHook.log("HDR bloom window unavailable: "+t);}}
 private void restoreWindow(View r){if(!changedWindow)return;try{WindowManager.LayoutParams p=(WindowManager.LayoutParams)r.getLayoutParams();p.setColorMode(oldMode);p.setDesiredHdrHeadroom(oldHeadroom);((WindowManager)r.getContext().getSystemService("window")).updateViewLayout(r,p);}catch(Throwable t){MiuiHook.log("Preview window restore: "+t);}changedWindow=false;}
 private void createBloom(){
  // A small reusable HDR color field; geometry and bloom are evaluated on the
  // GPU each frame, including the full-screen-to-thumbnail transition.
  int wheelSize=256;colorWheel=Bitmap.createBitmap(wheelSize,wheelSize,Bitmap.Config.ARGB_8888,true,ColorSpace.get(ColorSpace.Named.DISPLAY_P3));
  Paint colors=new Paint(Paint.ANTI_ALIAS_FLAG);colors.setShader(new SweepGradient(wheelSize/2f,wheelSize/2f,new int[]{0xff61dfff,0xffa18aff,0xffff83ba,0xffffb882,0xffa18aff,0xff61dfff},null));new Canvas(colorWheel).drawPaint(colors);
  if(hdrGlow){Bitmap gain=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);gain.eraseColor(Color.WHITE);Gainmap gm=new Gainmap(gain);float boost=Math.min(2.4f,ratio);gm.setRatioMin(1,1,1);gm.setRatioMax(boost,boost,boost);gm.setGamma(1,1,1);gm.setEpsilonSdr(0,0,0);gm.setEpsilonHdr(0,0,0);gm.setMinDisplayRatioForHdrTransition(1);gm.setDisplayRatioForFullHdr(boost);colorWheel.setGainmap(gm);}
  maskPaint.setShader(mask);maskPaint.setBlendMode(BlendMode.DST_IN);
  MiuiHook.log("Bloom color field hasGainmap="+colorWheel.hasGainmap()+" boost="+(hdrGlow?Math.min(2.4f,ratio):1)+" flowing="+animate+" realtimeGeometry=true");
 }
 @Override public void draw(Canvas canvas){ImageView v=host.get();View r=root.get();if(v==null||r==null||!v.isAttachedToWindow())return;
  transform.reset();v.transformMatrixToGlobal(transform);r.transformMatrixToLocal(transform);rect.set(0,0,v.getWidth(),v.getHeight());transform.mapRect(rect);
  // The preview dismisses through child RenderNode transforms. Those transforms do
  // not invalidate a drawable in the root overlay, so keep tracking until detach.
  r.postInvalidateOnAnimation();
  float hostAlpha=1;View ancestor=v;while(ancestor!=null){if(ancestor.getVisibility()!=View.VISIBLE)return;hostAlpha*=ancestor.getAlpha();if(ancestor==r)break;android.view.ViewParent parent=ancestor.getParent();ancestor=parent instanceof View?(View)parent:null;}
  int effectiveOpacity=Math.round(opacity*hostAlpha);if(effectiveOpacity<=0)return;
  long now=SystemClock.uptimeMillis();if(rect.width()<1||rect.height()<1)return;
  if(pulseStart==0)pulseStart=now;long elapsed=now-pulseStart;
  float radius=12*density;Drawable content=v.getDrawable();try{radius=((Number)content.getClass().getMethod("getCornerRadius").invoke(content)).floatValue()*rect.width()/v.getWidth();}catch(Throwable ignored){}
  if(glow){if(colorWheel==null){createBloom();enableWindow(r);}float padding=22*density;destination.set(rect.left-padding,rect.top-padding,rect.right+padding,rect.bottom+padding);paint.setShader(null);paint.setStyle(Paint.Style.FILL);
   int layer=canvas.saveLayer(destination,null);float cx=destination.centerX(),cy=destination.centerY(),half=(float)Math.hypot(destination.width(),destination.height())/2;
   wheelBounds.set(cx-half,cy-half,cx+half,cy+half);int rotated=canvas.save();if(animate)canvas.rotate((elapsed%3600)*0.1f,cx,cy);paint.setAlpha(255);canvas.drawBitmap(colorWheel,null,wheelBounds,paint);canvas.restoreToCount(rotated);
   float pulse=animate?0.94f+0.06f*(float)Math.sin(Math.PI*elapsed/1100f):1;mask.setFloatUniform("edge",rect.left,rect.top,rect.right,rect.bottom);mask.setFloatUniform("radius",radius);mask.setFloatUniform("density",density);mask.setFloatUniform("opacity",effectiveOpacity*pulse/255f);canvas.drawPaint(maskPaint);canvas.restoreToCount(layer);
  }
  if(badge){paint.setShader(null);paint.setStyle(Paint.Style.FILL);paint.setTextSize(10*density);float tw=paint.measureText("HDR"),bw=tw+12*density,bh=19*density,x=rect.right-bw-8*density,y=rect.top+8*density;destination.set(x,y,x+bw,y+bh);paint.setColor(0xe61b2639);paint.setAlpha(225*effectiveOpacity/255);canvas.drawRoundRect(destination,5*density,5*density,paint);paint.setColor(0xffddf8ff);paint.setAlpha(effectiveOpacity);canvas.drawText("HDR",x+6*density,y+(bh-paint.ascent()-paint.descent())/2,paint);}
 }
 static void beforeCapture(){final CountDownLatch cleared=new CountDownLatch(1);Runnable work=()->{try{boolean any=!FRAMES.isEmpty();for(ImageView v:new ArrayList<ImageView>(FRAMES.keySet()))if(v!=null)clear(v);if(any)MiuiHook.log("Previous HDR bloom removed before capture");}finally{cleared.countDown();}};
  if(Looper.myLooper()==Looper.getMainLooper()){work.run();return;}
  new Handler(Looper.getMainLooper()).post(work);try{if(!cleared.await(400,TimeUnit.MILLISECONDS))MiuiHook.log("Preview clear delayed; capture layer exclusion remains active");}catch(InterruptedException e){Thread.currentThread().interrupt();}
 }
 @Override public void setAlpha(int a){opacity=a;invalidateSelf();}@Override public void setColorFilter(ColorFilter f){}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
 public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){if(v instanceof ImageView)clear((ImageView)v);}
}
