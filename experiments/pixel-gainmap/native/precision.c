#include <jni.h>
#include <android/bitmap.h>
#include <stdint.h>
#include <math.h>

/* No spatial filtering: reconstruct and rebase each pixel independently.
 * Ordinary pixels receive unit gain. For highlights, scale linear RGB by its
 * maximum to preserve chromaticity; encode the remaining ratio separately.
 */
static int encode_linear(float value,const float *linear,int floor_only){
 int lo=0,hi=255;while(lo<hi){int mid=(lo+hi+1)/2;if(linear[mid]<=value)lo=mid;else hi=mid-1;}
 if(!floor_only&&lo<255&&value-linear[lo]>linear[lo+1]-value)lo++;
 return lo;
}
JNIEXPORT jint JNICALL Java_dev_local_miuihdr_NativeBridge_rebaseGainmap(JNIEnv *env,jclass clazz,jobject source,jobject gain,jobject result,jobject output,jfloatArray params,jfloatArray transfer){
 jobject objects[4]={source,gain,result,output};AndroidBitmapInfo info[4];unsigned char *pixels[4]={0};int locked=0,answer=-1,count=0;
 if((*env)->GetArrayLength(env,params)!=21||(*env)->GetArrayLength(env,transfer)!=256)return -2;
 float m[21],linear[256],factor[3][256],factor_low[3][256],maximum[3]={1.0001f,1.0001f,1.0001f};const float eps=1.f/64;
 (*env)->GetFloatArrayRegion(env,params,0,21,m);(*env)->GetFloatArrayRegion(env,transfer,0,256,linear);
 for(int c=0;c<3;c++){
  if(!(m[c]>0&&m[c+3]>0&&m[c+6]>0&&isfinite(m[c])&&isfinite(m[c+3])&&isfinite(m[c+6])&&isfinite(m[c+9])&&isfinite(m[c+12])))return -3;
  for(int v=0;v<256;v++){
   factor[c][v]=expf(logf(m[c])+(logf(m[c+3])-logf(m[c]))*powf(v/255.f,m[c+6]));
   factor_low[c][v]=expf(logf(m[c])+(logf(m[c+3])-logf(m[c]))*powf(fmaxf(0,v-.5f)/255.f,m[c+6]));
  }
 }
 for(int i=0;i<4;i++){
  if(AndroidBitmap_getInfo(env,objects[i],&info[i])||(info[i].flags&ANDROID_BITMAP_FLAGS_IS_HARDWARE))goto finish;
  if(info[i].format!=ANDROID_BITMAP_FORMAT_RGBA_8888&&!(i==1&&info[i].format==ANDROID_BITMAP_FORMAT_A_8))goto finish;
  if(i&&(info[i].width!=info[0].width||info[i].height!=info[0].height))goto finish;
  if(AndroidBitmap_lockPixels(env,objects[i],(void **)&pixels[i])||!pixels[i])goto finish;locked++;
 }
 if((size_t)info[0].width*info[0].height>16000000)goto finish;
 for(int pass=0;pass<2;pass++)for(uint32_t y=0;y<info[0].height;y++)for(uint32_t x=0;x<info[0].width;x++){
  const unsigned char *s=pixels[0]+y*info[0].stride+4*x,*g=pixels[1]+y*info[1].stride+(info[1].format==ANDROID_BITMAP_FORMAT_A_8?x:4*x);
  unsigned char *b=pixels[2]+y*info[2].stride+4*x,*o=pixels[3]+y*info[3].stride+4*x;
  // Captures are opaque. Real transparent images need a separate compositor.
  if(s[3]!=255){answer=-5;goto finish;}
  float H[3],peak=0,peak_low=0;
  for(int c=0;c<3;c++){
   int v=s[3]?((int)s[c]*255+s[3]/2)/s[3]:0;if(v>255)v=255;
   int q=info[1].format==ANDROID_BITMAP_FORMAT_A_8?g[0]:g[c];
   H[c]=s[3]?fmaxf(0,(linear[v]+m[c+9])*factor[c][q]-m[c+12]):0;
   if(!isfinite(H[c]))goto finish;peak=fmaxf(peak,H[c]);
   float base_low=v?(linear[v]+linear[v-1])*.5f:0;
   peak_low=fmaxf(peak_low,(base_low+m[c+9])*factor_low[c][q]-m[c+12]);
  }
  // A white pixel can round just above 1 in the original 8-bit pair. Require
  // its lower quantization bound to exceed white, not an arbitrary spatial mask.
  int highlight=peak_low>1.f;
  if(pass==0){
   if(highlight)count++;
   for(int c=0;c<3;c++){
    int v=encode_linear(highlight?H[c]/peak:H[c],linear,highlight);
    b[c]=(unsigned char)((v*s[3]+127)/255);
    int effective=s[3]?((int)b[c]*255+s[3]/2)/s[3]:0;if(effective>255)effective=255;
    if(highlight)maximum[c]=fmaxf(maximum[c],(H[c]+eps)/(linear[effective]+eps));
   }b[3]=s[3];
  }else{
   for(int c=0;c<3;c++){
    int v=b[3]?((int)b[c]*255+b[3]/2)/b[3]:0;if(v>255)v=255;
    float ratio=highlight?(H[c]+eps)/(linear[v]+eps):1.f;
    // Premultiplied 8-bit alpha can round upward; retain neutral low pixels.
    float encoded=logf(fmaxf(1,ratio))/logf(maximum[c]);
    o[c]=(unsigned char)lroundf(fmaxf(0,fminf(1,encoded))*255);
   }o[3]=255;
  }
 }
 for(int c=0;c<3;c++){m[15+c]=1;m[18+c]=maximum[c];}
 (*env)->SetFloatArrayRegion(env,params,0,21,m);answer=count;
 finish:for(int i=locked-1;i>=0;i--)AndroidBitmap_unlockPixels(env,objects[i]);return answer;
}
