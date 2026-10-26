#pragma once
#include <stdint.h>
#include <math.h>
#include <string.h>

/* Raw capture values are never sampled from neighboring pixels. */
static float hdr_half(uint16_t h) {
 unsigned sign=h>>15,exp=(h>>10)&31,mantissa=h&1023;
 float value=exp==0?ldexpf((float)mantissa,-24):exp==31?(mantissa?NAN:INFINITY):ldexpf((float)(1024+mantissa),(int)exp-25);
 return sign?-value:value;
}
static float hdr_decode(float value,int linear) {
 if(linear)return value;
 return value<=0.04045f?value/12.92f:powf((value+0.055f)/1.055f,2.4f);
}
static float hdr_encode(float value,int linear) {
 if(linear)return value;
 return value<=0.0031308f?12.92f*value:1.055f*powf(value,1.f/2.4f)-0.055f;
}
static uint8_t hdr_base_byte(float value,int linear) {
 return (uint8_t)lroundf(fminf(1,fmaxf(0,hdr_encode(value,linear)))*255.f);
}
static uint8_t hdr_gain_byte(float gain,float maximum) {
 if(gain<=1||maximum<=1)return 0;
 return (uint8_t)lroundf(fminf(1,fmaxf(0,logf(gain)/logf(maximum)))*255.f);
}
struct hdr_sample {float value;uint8_t base;uint8_t highlight;};
static struct hdr_sample hdr_sample_value(uint16_t half,float scale,int linear) {
 float encoded=hdr_half(half),value=hdr_decode(encoded,linear)*scale;
 // A half-float white can round slightly above SDR white. Only classify an
 // actual highlight when its lower quantization bound is above white too.
 float lower=half>0&&half<0x7c00?hdr_decode((encoded+hdr_half(half-1))*.5f,linear)*scale:value;
 struct hdr_sample result={value,0,lower>1.f};
 if(isfinite(value)&&value>=0&&value<=10000) {
  if(!result.highlight&&value>1)result.value=1;
  // The 8-bit base always uses the sRGB/P3 transfer, including linear input.
  result.base=hdr_base_byte(fminf(result.value,1.f),0);
 }
 return result;
}
