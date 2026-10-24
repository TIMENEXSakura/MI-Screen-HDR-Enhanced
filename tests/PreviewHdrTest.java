package dev.local.miuihdr;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.ImageView;
import java.lang.reflect.*;

/** Android runtime lifecycle test, with a recording window manager; creates no window. */
public final class PreviewHdrTest {
 static int passed,updates;
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);passed++;System.out.println("PASS "+why);}
 static class Root extends View {
  boolean attached=true;
  Root(Context c){super(c);}
  public boolean isAttachedToWindow(){return attached;}
 }
 static class Picture extends ImageView {
  final Root root;
  Picture(Context c,Root r){super(c);root=r;}
  public View getRootView(){return root;}
  public boolean isAttachedToWindow(){return root.attached;}
 }
 static WindowManager.LayoutParams params(Root r){return (WindowManager.LayoutParams)r.getLayoutParams();}
 public static void main(String[] args)throws Exception{
  Looper.prepareMainLooper();
  Class<?> thread=Class.forName("android.app.ActivityThread");Object at=thread.getMethod("systemMain").invoke(null);
  Context system=(Context)thread.getMethod("getSystemContext").invoke(at);
  WindowManager recorder=(WindowManager)Proxy.newProxyInstance(PreviewHdrTest.class.getClassLoader(),new Class[]{WindowManager.class},(p,m,a)->{
   if(m.getName().equals("updateViewLayout")){updates++;((View)a[0]).setLayoutParams((ViewGroup.LayoutParams)a[1]);return null;}
   throw new UnsupportedOperationException(m.getName());
  });
  Context context=new ContextWrapper(system){public Object getSystemService(String n){return WINDOW_SERVICE.equals(n)?recorder:super.getSystemService(n);}};
  Root root=new Root(context);WindowManager.LayoutParams initial=new WindowManager.LayoutParams();
  initial.setColorMode(ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT);initial.setDesiredHdrHeadroom(1.5f);initial.flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
  root.setLayoutParams(initial);Picture image=new Picture(context,root);image.setLayerType(View.LAYER_TYPE_HARDWARE,null);
  Bitmap bitmap=PrecisionCodecTest.source(false);image.setImageBitmap(bitmap);
  PreviewHdr.bind(image,new HdrSignal.Info(true,4,255));
  check(params(root).getColorMode()==ActivityInfo.COLOR_MODE_HDR,"HDR window independent of glow options");
  check(params(root).getDesiredHdrHeadroom()==4,"content metadata headroom requested");
  check(image.getLayerType()==View.LAYER_TYPE_NONE&&PreviewHdr.directDraw(image),"direct hardware draw selected for preview only");
  check(!PreviewHdr.directDraw(new View(context)),"unrelated views retain original layer policy");
  check(((android.graphics.drawable.BitmapDrawable)image.getDrawable()).getBitmap()==bitmap&&bitmap.hasGainmap(),"original bitmap and gainmap retained without conversion");
  check(params(root).flags==initial.flags,"window focus flags preserved");
  WindowManager.LayoutParams touchUpdate=new WindowManager.LayoutParams();touchUpdate.copyFrom(initial);touchUpdate.width=300;
  WindowManager.LayoutParams retained=(WindowManager.LayoutParams)PreviewHdr.keepHdr(root,touchUpdate);
  check(retained.getColorMode()==ActivityInfo.COLOR_MODE_HDR&&retained.width==300,"HDR survives Xiaomi touch-region window updates");
  check(touchUpdate.getColorMode()==ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT,"Xiaomi saved window parameters stay unchanged");
  PreviewHdr.clearAll();
  check(params(root).getColorMode()==ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT&&params(root).getDesiredHdrHeadroom()==1.5f,"capture isolation restores original window range");
  check(image.getLayerType()==View.LAYER_TYPE_HARDWARE&&!PreviewHdr.directDraw(image),"original layer restored");
  check(PreviewHdr.keepHdr(root,touchUpdate)==touchUpdate,"future SDR window updates are unmodified");
  int count=updates;PreviewHdr.clearAll();check(updates==count,"repeated cleanup is harmless");
  for(int i=0;i<3;i++){PreviewHdr.bind(image,new HdrSignal.Info(true,4,255));PreviewHdr.bind(image,new HdrSignal.Info(false,1,0));}
  check(params(root).getColorMode()==ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT&&!PreviewHdr.directDraw(image),"repeated HDR to SDR transitions leave no HDR state");
  root.attached=false;count=updates;PreviewHdr.bind(image,new HdrSignal.Info(true,4,255));
  check(updates==count,"detached view does not update window");PreviewHdr.clear(image);
  check(!PreviewHdr.directDraw(image)&&image.getLayerType()==View.LAYER_TYPE_HARDWARE,"detached cleanup releases preview state");
  bitmap.recycle();System.out.println("PREVIEW_HDR_CHECKS="+passed);System.exit(0);
 }
}
