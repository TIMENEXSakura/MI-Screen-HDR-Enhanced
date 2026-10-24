#include <jni.h>
#include <android/bitmap.h>
#include <stdint.h>
#include <math.h>
#include <stdlib.h>

/* No spatial filtering: reconstruct and rebase each pixel independently.
 * Ordinary pixels receive unit gain. For highlights, scale linear RGB by its
 * maximum to preserve chromaticity; encode the remaining ratio separately.
 */
static int encode_linear(float value,const float *linear,int floor_only){
 int lo=0,hi=255;while(lo<hi){int mid=(lo+hi+1)/2;if(linear[mid]<=value)lo=mid;else hi=mid-1;}
 if(!floor_only&&lo<255&&value-linear[lo]>linear[lo+1]-value)lo++;
 return lo;
}
/* Exact tables for the finite 8-bit input pairs, not a lower-resolution map.
 * Every output pixel uses its own original base and gain byte. */
struct precision_pair {float hdr;unsigned char sdr,highlight;};
JNIEXPORT jint JNICALL Java_dev_local_miuihdr_NativeBridge_rebaseGainmap(JNIEnv *env,jclass clazz,jobject source,jobject gain,jobject result,jobject output,jfloatArray params,jfloatArray transfer){
 jobject objects[4]={source,gain,result,output};AndroidBitmapInfo info[4];unsigned char *pixels[4]={0};int locked=0,answer=-1,count=0;
 struct precision_pair (*pairs)[65536]=NULL;
 if((*env)->GetArrayLength(env,params)!=21||(*env)->GetArrayLength(env,transfer)!=256)return -2;
 float m[21],linear[256],factor[3][256],factor_low[3][256],maximum[3]={1.0001f,1.0001f,1.0001f};const float eps=1.f/64;
 (*env)->GetFloatArrayRegion(env,params,0,21,m);(*env)->GetFloatArrayRegion(env,transfer,0,256,linear);
 for(int c=0;c<3;c++){
  if(!(m[c]>0&&m[c+3]>=m[c]&&m[c+6]>0&&isfinite(m[c])&&isfinite(m[c+3])&&isfinite(m[c+6])&&isfinite(m[c+9])&&isfinite(m[c+12])))return -3;
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
 pairs=malloc(3*sizeof(*pairs));if(!pairs)goto finish;
 for(int c=0;c<3;c++)for(int v=0;v<256;v++)for(int q=0;q<256;q++){
  struct precision_pair *p=&pairs[c][v*256+q];
  p->hdr=fmaxf(0,(linear[v]+m[c+9])*factor[c][q]-m[c+12]);
  if(!isfinite(p->hdr))goto finish;
  p->sdr=encode_linear(p->hdr,linear,0);
  float low=v?(linear[v]+linear[v-1])*.5f:0;
  p->highlight=(low+m[c+9])*factor_low[c][q]-m[c+12]>1.f;
 }
 for(uint32_t y=0;y<info[0].height;y++)for(uint32_t x=0;x<info[0].width;x++){
  const unsigned char *s=pixels[0]+y*info[0].stride+4*x,*g=pixels[1]+y*info[1].stride+(info[1].format==ANDROID_BITMAP_FORMAT_A_8?x:4*x);
  unsigned char *b=pixels[2]+y*info[2].stride+4*x,*o=pixels[3]+y*info[3].stride+4*x;
  // Captures are opaque. Real transparent images need a separate compositor.
  if(s[3]!=255){answer=-5;goto finish;}
  const struct precision_pair *p[3];float peak=0;int highlight=0;
  for(int c=0;c<3;c++){
   int q=info[1].format==ANDROID_BITMAP_FORMAT_A_8?g[0]:g[c];
   p[c]=&pairs[c][s[c]*256+q];peak=fmaxf(peak,p[c]->hdr);highlight|=p[c]->highlight;
  }
  // A white pixel can round just above 1 in the original 8-bit pair. Require
  // its lower quantization bound to exceed white, not an arbitrary spatial mask.
  if(highlight)count++;
  for(int c=0;c<3;c++){
   b[c]=highlight?encode_linear(p[c]->hdr/peak,linear,1):p[c]->sdr;
   if(highlight)maximum[c]=fmaxf(maximum[c],(p[c]->hdr+eps)/(linear[b[c]]+eps));
   o[c]=0;
  }
  b[3]=255;o[3]=highlight?0:255;
 }
 float log_max[3];for(int c=0;c<3;c++)log_max[c]=logf(maximum[c]);
 for(uint32_t y=0;y<info[0].height;y++)for(uint32_t x=0;x<info[0].width;x++){
  unsigned char *o=pixels[3]+y*info[3].stride+4*x;
  // Ordinary pixels are already final. Avoid reconstructing/log-encoding them
  // for a second time; the private output alpha is a temporary highlight flag.
  if(o[3]==255)continue;
  const unsigned char *s=pixels[0]+y*info[0].stride+4*x,*g=pixels[1]+y*info[1].stride+(info[1].format==ANDROID_BITMAP_FORMAT_A_8?x:4*x),*b=pixels[2]+y*info[2].stride+4*x;
  for(int c=0;c<3;c++){
   int q=info[1].format==ANDROID_BITMAP_FORMAT_A_8?g[0]:g[c];
   float ratio=(pairs[c][s[c]*256+q].hdr+eps)/(linear[b[c]]+eps);
   float encoded=logf(fmaxf(1,ratio))/log_max[c];
   o[c]=(unsigned char)lroundf(fmaxf(0,fminf(1,encoded))*255);
  }o[3]=255;
 }
 for(int c=0;c<3;c++){m[15+c]=1;m[18+c]=maximum[c];}
 (*env)->SetFloatArrayRegion(env,params,0,21,m);answer=count;
 finish:free(pairs);for(int i=locked-1;i>=0;i--)AndroidBitmap_unlockPixels(env,objects[i]);return answer;
}
