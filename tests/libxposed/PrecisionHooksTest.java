package dev.local.miuihdr;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Exercise real API 102 interceptors with an in-memory framework and encoder. */
public final class PrecisionHooksTest {
 private static final Map<String,Hooker> hooks=new HashMap<>();
 private static final Map<String,ExceptionMode> modes=new HashMap<>();
 private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 private static XposedInterface framework(){
  return (XposedInterface)Proxy.newProxyInstance(PrecisionHooksTest.class.getClassLoader(),new Class<?>[]{XposedInterface.class},(proxy,method,args)->{
   if(!method.getName().equals("hook"))throw new UnsupportedOperationException(method.toString());
   String[] id={null};ExceptionMode[] mode={ExceptionMode.DEFAULT};
   return Proxy.newProxyInstance(PrecisionHooksTest.class.getClassLoader(),new Class<?>[]{HookBuilder.class},(builder,call,values)->{
    switch(call.getName()){
     case "setId":id[0]=(String)values[0];return builder;
     case "setExceptionMode":mode[0]=(ExceptionMode)values[0];return builder;
     case "intercept":hooks.put(id[0],(Hooker)values[0]);modes.put(id[0],mode[0]);return null;
     default:throw new UnsupportedOperationException(call.toString());
    }
   });
  });
 }
 private static final class Call implements Chain {
  final Object receiver;final Object[] args;int proceeds;Throwable failure;
  Call(Object receiver,Object... args){this.receiver=receiver;this.args=args;}
  public Executable getExecutable(){return null;}
  public Object getThisObject(){return receiver;}
  public List<Object> getArgs(){return Collections.unmodifiableList(Arrays.asList(args));}
  public Object getArg(int index){return args[index];}
  public Object proceed()throws Throwable{proceeds++;if(failure!=null)throw failure;return true;}
  public Object proceed(Object[] values)throws Throwable{return proceed();}
  public Object proceedWith(Object receiver)throws Throwable{return proceed();}
  public Object proceedWith(Object receiver,Object[] values)throws Throwable{return proceed();}
 }
 private static Call jpeg(Bitmap bitmap,OutputStream output){return new Call(bitmap,Bitmap.CompressFormat.JPEG,100,output);}
 private static Object encode(Call call)throws Throwable{return hooks.get("precision.jpeg444").intercept(call);}
 public static void main(String[] args)throws Throwable{
  PrecisionHooks.install(framework());
  check(modes.get("precision.jpeg444")==ExceptionMode.PASSTHROUGH,"partial writes must not trigger framework fallback");
  Bitmap hdr=new Bitmap(true);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  Call call=jpeg(hdr,bytes);encode(call);check(call.proceeds==1&&bytes.size()==0,"before Application attaches, retain encoder");
  Call attach=new Call(new Application(),new Context());hooks.get("precision.context").intercept(attach);
  check(attach.proceeds==1&&PrecisionHooks.enabled(),"Application context is captured after attach");
  call=jpeg(hdr,bytes);check(Boolean.TRUE.equals(encode(call))&&call.proceeds==0&&Arrays.equals(bytes.toByteArray(),HdrJpeg444.DATA),"HDR encodes once without original JPEG");
  call=jpeg(new Bitmap(false),new ByteArrayOutputStream());encode(call);check(call.proceeds==1,"SDR uses original");
  call=new Call(hdr,Bitmap.CompressFormat.PNG,100,new ByteArrayOutputStream());encode(call);check(call.proceeds==1,"PNG uses original");
  HookConfig.active=false;call=jpeg(hdr,new ByteArrayOutputStream());encode(call);check(call.proceeds==1,"disabled precision uses original");HookConfig.active=true;
  HdrJpeg444.fail=true;call=jpeg(hdr,new ByteArrayOutputStream());encode(call);check(call.proceeds==1,"encoder failure falls back before write");HdrJpeg444.fail=false;
  call=jpeg(hdr,new ByteArrayOutputStream());encode(call);check(call.proceeds==0,"encoder guard clears after failure");
  Call nested=jpeg(hdr,new ByteArrayOutputStream());HdrJpeg444.duringEncode=()->{try{encode(nested);}catch(Throwable t){throw new AssertionError(t);}};
  call=jpeg(hdr,new ByteArrayOutputStream());encode(call);check(nested.proceeds==1&&call.proceeds==0,"recursive JPEG calls use original once");HdrJpeg444.duringEncode=null;
  IOException diskFull=new IOException("disk full");int[] written={0};
  call=jpeg(hdr,new OutputStream(){public void write(int value)throws IOException{if(written[0]++==1)throw diskFull;}});
  try{encode(call);throw new AssertionError("partial failure swallowed");}catch(IOException expected){check(expected==diskFull&&call.proceeds==0&&written[0]==2,"partial write never retries original encoder");}
  HookConfig.active=false;call=jpeg(hdr,new ByteArrayOutputStream());call.failure=diskFull;
  try{encode(call);throw new AssertionError("original failure swallowed");}catch(IOException expected){check(expected==diskFull&&call.proceeds==1,"original throwable propagates unchanged");}
  WeakIdentityMap<String> states=new WeakIdentityMap<>();Object first=new String("same"),second=new String("same");
  states.put(first,"first");states.put(second,"second");check("first".equals(states.get(first))&&"second".equals(states.get(second)),"equal objects have isolated state");
  states.put(first,null);check(states.get(first)==null&&"second".equals(states.get(second)),"state removal is per identity");
  System.out.println("PASS: API 102 context, JPEG success/fallback/reentrancy/partial I/O failure, identity state");
 }
}
