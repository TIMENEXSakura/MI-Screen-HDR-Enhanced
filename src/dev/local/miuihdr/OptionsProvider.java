package dev.local.miuihdr;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
public final class OptionsProvider extends ContentProvider {
 public boolean onCreate(){return true;}
 @Override public Bundle call(String method,String arg,Bundle extras){int uid=Binder.getCallingUid();boolean allowed=uid==android.os.Process.myUid()||uid==0||uid==1000;String[] packages=getContext().getPackageManager().getPackagesForUid(uid);if(packages!=null)for(String p:packages)if("com.miui.screenshot".equals(p))allowed=true;if(!allowed)throw new SecurityException("Caller is not the screenshot app");if(!"options".equals(method))throw new IllegalArgumentException("Unknown method");SharedPreferences p=getContext().getSharedPreferences("ui_options",Context.MODE_PRIVATE);Bundle b=new Bundle();b.putBoolean("glow",p.getBoolean("glow",true));b.putBoolean("hdr_glow",p.getBoolean("hdr_glow",true));b.putBoolean("badge",p.getBoolean("badge",true));b.putBoolean("pixel_precision",p.getBoolean("pixel_precision",false));return b;}
 public String getType(Uri u){return null;}public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
