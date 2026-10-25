package dev.local.miuihdr;

import android.graphics.Bitmap;
import java.util.Objects;

/** Apply the base image's integer crop to its gainmap in Android's own pipeline. */
final class CropGainmap {
 static Bitmap restore(Bitmap source,Bitmap result,int left,int top,int width,int height){
  if(source==null||result==null||source==result||source.isRecycled()||result.isRecycled()||!source.hasGainmap()||result.hasGainmap())return result;
  if(left<0||top<0||width<=0||height<=0||(long)left+width>source.getWidth()||(long)top+height>source.getHeight()
    ||result.getWidth()!=width||result.getHeight()!=height)return result;
  Bitmap crop=Bitmap.createBitmap(source,left,top,width,height);
  if(!crop.hasGainmap())return result;
  // The long-screenshot crop creates an sRGB canvas even for Display P3 input.
  // Keep the original cropped base in that case, instead of attaching P3 gain
  // metadata to already clipped sRGB pixels.
  if(!Objects.equals(result.getColorSpace(),source.getColorSpace()))return crop;
  result.setGainmap(crop.getGainmap());
  // The gainmap now belongs to result too; leave native/Java lifetime to GC.
  return result;
 }
}
