package dev.local.miuihdr;
public final class HdrTestActivity extends android.app.Activity {
 @Override public void onCreate(android.os.Bundle b){super.onCreate(b);startActivity(new android.content.Intent(this,SettingsActivity.class).putExtra("page",1));finish();}
}
