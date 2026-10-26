package dev.local.miuihdr;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
public final class OptionsProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 @Override public Bundle call(String method,String arg,Bundle extras){
  int uid=Binder.getCallingUid();boolean own=uid==android.os.Process.myUid()||uid==0||uid==1000;
  boolean screenshot=false,recorder=false;String[] packages=getContext().getPackageManager().getPackagesForUid(uid);
  if(packages!=null)for(String name:packages){if("com.miui.screenshot".equals(name))screenshot=true;if("com.miui.screenrecorder".equals(name))recorder=true;}
  if(method.startsWith("recorder_")){
   if(!own&&!recorder)throw new SecurityException("Caller is not the recorder app");
   if("recorder_settings".equals(method)){
    if(extras!=null&&extras.getBoolean("refresh",false))RecorderModuleBridge.refresh();
    return RecorderSettings.read(getContext());
   }
   if("recorder_configure".equals(method))return RecorderSettings.write(getContext(),extras);
   if("recorder_ping".equals(method)){
    Bundle reply=RecorderModuleBridge.ping(Binder.getCallingPid(),extras);
    reply.putAll(RecorderSettings.read(getContext()));return reply;
   }
   if("recorder_select".equals(method))return RecorderModuleBridge.select(extras);
   throw new IllegalArgumentException("Unknown recorder method");
  }
  if(!own&&!screenshot)throw new SecurityException("Caller is not the screenshot app");
  if(!"options".equals(method))throw new IllegalArgumentException("Unknown method");
  SharedPreferences p=getContext().getSharedPreferences("ui_options",Context.MODE_PRIVATE);Bundle b=new Bundle();b.putBoolean("glow",p.getBoolean("glow",true));b.putBoolean("hdr_glow",p.getBoolean("hdr_glow",true));b.putBoolean("badge",p.getBoolean("badge",true));b.putBoolean("pixel_precision",p.getBoolean("pixel_precision",false));b.putBoolean("capture_ratio_auto",p.getBoolean("capture_ratio_auto",true));b.putString("capture_ratio_mode",p.getString("capture_ratio_mode",CaptureRatio.FORCE));b.putFloat("capture_ratio_offset",p.getFloat("capture_ratio_offset",0));b.putFloat("capture_ratio",p.getFloat("capture_ratio",4));return b;}
 public String getType(Uri u){return null;}public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
