package dev.local.miuihdr;

import android.util.Log;

/** Shared by the settings app, root tools and injected hooks; no framework API dependency. */
final class HdrLog {
 private HdrLog(){}
 static void log(String message){Log.i("MiuiHdrHook",message);}
}
