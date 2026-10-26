package dev.local.miuihdr;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Module-owned call state backed exclusively by libxposed 102 interceptors.
 * Original exceptions and explicit I/O failures pass through unchanged; the
 * original operation is never retried after it has already run or written data. */
final class HookRuntime {
 private static XposedInterface framework;
 private static int hooks;
 static void initialize(XposedInterface api){framework=api;}
 static boolean deoptimize(java.lang.reflect.Executable method){return framework.deoptimize(method);}
 static int count(){return hooks;}
 static int apiVersion(){return framework==null?0:framework.getApiVersion();}
 static void log(String message){
  if(framework!=null)framework.log(android.util.Log.INFO,"MiuiHdrHook",message);
  else android.util.Log.i("MiuiHdrHook",message);
 }
 static XposedInterface.HookHandle add(Method method,Callback callback){
  if(framework==null)throw new IllegalStateException("libxposed is not attached");
  XposedInterface.HookHandle handle=framework.hook(method)
   .setId("mi-screen-hdr:"+method.toGenericString())
   .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
   .intercept(chain->dispatch(chain,callback));
  hooks++;return handle;
 }
 static Object dispatch(XposedInterface.Chain chain,Callback callback)throws Throwable{
  Call call=new Call(chain.getThisObject(),chain.getArgs().toArray());
  Object[] originalArgs=call.args.clone();
  try{callback.before(call);}catch(Throwable failure){
   log("Hook before failed; native call retained: "+failure);call.args=originalArgs;call.result=null;call.error=null;call.skip=false;
  }
  if(!call.skip)try{call.result=chain.proceed(call.args);}catch(Throwable original){call.error=original;}
  Object result=call.result;Throwable error=call.error;
  try{callback.after(call);}catch(Throwable failure){log("Hook after failed; original outcome retained: "+failure);call.result=result;call.error=error;}
  if(call.error!=null)throw call.error;
  return call.result;
 }
 abstract static class Callback {
  protected void before(Call call)throws Throwable{}
  protected void after(Call call)throws Throwable{}
 }
 static final class Call {
  final Object thisObject;
  Object[] args;
  private Object result;
  private Throwable error;
  private boolean skip;
  private Map<String,Object> extras;
  Call(Object receiver,Object[] args){thisObject=receiver;this.args=args;}
  Object getResult(){return result;}
  boolean hasThrowable(){return error!=null;}
  void setResult(Object value){result=value;error=null;skip=true;}
  void setThrowable(Throwable value){error=value;result=null;skip=true;}
  Object getObjectExtra(String key){return extras==null?null:extras.get(key);}
  void setObjectExtra(String key,Object value){if(extras==null)extras=new HashMap<>();extras.put(key,value);}
 }
}
