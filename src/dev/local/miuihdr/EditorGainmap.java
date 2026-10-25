package dev.local.miuihdr;
import android.graphics.*;

/** Restore only the image's enhancement, using exactly the editor's crop. */
final class EditorGainmap {
 static boolean restore(Bitmap source,Bitmap display,int top){
  if(source==null||display==null||source.isRecycled()||display.isRecycled())return false;
  HdrSignal.invalidate(display);
  if(!source.hasGainmap()){display.setGainmap(null);return false;}
  if(source.getWidth()!=display.getWidth()||top<0||top+display.getHeight()>source.getHeight())return false;
  if(top==0&&source.getHeight()==display.getHeight()){display.setGainmap(source.getGainmap());return true;}
  Bitmap crop=Bitmap.createBitmap(source,0,top,display.getWidth(),display.getHeight());
  // Android crops the gainmap in the same coordinate system as the base.
  // Do not round an independent gainmap rectangle or scale gain into the shell.
  if(!crop.hasGainmap())return false;
  display.setGainmap(crop.getGainmap());return true;
 }
}
