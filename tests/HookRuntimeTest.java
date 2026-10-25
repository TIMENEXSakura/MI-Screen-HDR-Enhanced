package dev.local.miuihdr;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.*;
import java.io.IOException;
import java.util.*;

public final class HookRuntimeTest {
 static int passed;
 static XposedInterface.Hooker registered;
 static String id;
 static XposedInterface.ExceptionMode mode;
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);passed++;System.out.println("PASS "+message);}
 public static int target(int value){return value*2;}
 static class FakeChain implements XposedInterface.Chain {
  int calls;Throwable failure;Object[] input={3};Object receiver=new Object();
  public Executable getExecutable(){try{return HookRuntimeTest.class.getMethod("target",int.class);}catch(Exception e){throw new AssertionError(e);}}
  public Object getThisObject(){return receiver;}
  public List<Object> getArgs(){return Collections.unmodifiableList(Arrays.asList(input));}
  public Object getArg(int index){return input[index];}
  public Object proceed()throws Throwable{return proceed(input);}
  public Object proceed(Object[] args)throws Throwable{calls++;if(failure!=null)throw failure;return target((Integer)args[0]);}
  public Object proceedWith(Object self)throws Throwable{return proceed(input);}
  public Object proceedWith(Object self,Object[] args)throws Throwable{return proceed(args);}
 }
 public static void main(String[] ignored)throws Throwable{
  ClassLoader loader=HookRuntimeTest.class.getClassLoader();
  Object builder=Proxy.newProxyInstance(loader,new Class[]{XposedInterface.HookBuilder.class},(p,m,a)->{
   if(m.getName().equals("setId")){id=(String)a[0];return p;}
   if(m.getName().equals("setExceptionMode")){mode=(XposedInterface.ExceptionMode)a[0];return p;}
   if(m.getName().equals("intercept")){registered=(XposedInterface.Hooker)a[0];return null;}
   throw new AssertionError(m);
  });
  XposedInterface framework=(XposedInterface)Proxy.newProxyInstance(loader,new Class[]{XposedInterface.class},(p,m,a)->{
   if(m.getName().equals("hook"))return builder;if(m.getName().equals("log"))return null;throw new AssertionError(m);
  });
  HookRuntime.initialize(framework);
  HookRuntime.add(HookRuntimeTest.class.getMethod("target",int.class),new HookRuntime.Callback(){
   protected void before(HookRuntime.Call c){c.args[0]=5;c.setObjectExtra("adjust",2);}
   protected void after(HookRuntime.Call c){c.setResult((Integer)c.getResult()+(Integer)c.getObjectExtra("adjust"));}
  });
  FakeChain chain=new FakeChain();check(registered.intercept(chain).equals(12)&&chain.calls==1,"modern interceptor modifies args/result exactly once");
  check(id.startsWith("mi-screen-hdr:")&&mode==XposedInterface.ExceptionMode.PASSTHROUGH,"API 102 hook ID and explicit exception policy");
  chain=new FakeChain();Object result=HookRuntime.dispatch(chain,new HookRuntime.Callback(){protected void before(HookRuntime.Call c){c.setResult(null);}});
  check(result==null&&chain.calls==0,"void shell toggle can skip original operation");
  final IOException io=new IOException("partial JPEG write");chain=new FakeChain();
  try{HookRuntime.dispatch(chain,new HookRuntime.Callback(){protected void before(HookRuntime.Call c){c.setThrowable(io);}});throw new AssertionError();}catch(IOException e){check(e==io&&chain.calls==0,"I/O failure never retries original encoder");}
  chain=new FakeChain();chain.failure=io;final boolean[] cleaned={false};
  try{HookRuntime.dispatch(chain,new HookRuntime.Callback(){protected void after(HookRuntime.Call c){cleaned[0]=c.hasThrowable();}});throw new AssertionError();}catch(IOException e){check(e==io&&chain.calls==1&&cleaned[0],"original failure preserved and cleanup invoked");}
  chain=new FakeChain();result=HookRuntime.dispatch(chain,new HookRuntime.Callback(){protected void before(HookRuntime.Call c){c.args[0]=99;throw new IllegalStateException("optional hook");}});
  check(result.equals(6)&&chain.calls==1,"failed before hook restores original arguments");
  chain=new FakeChain();result=HookRuntime.dispatch(chain,new HookRuntime.Callback(){protected void after(HookRuntime.Call c){c.setResult(99);throw new IllegalStateException("optional preview");}});
  check(result.equals(6)&&chain.calls==1,"failed after hook preserves result without replay");
  chain=new FakeChain();chain.failure=io;
  try{HookRuntime.dispatch(chain,new HookRuntime.Callback(){protected void after(HookRuntime.Call c){throw new IllegalStateException();}});throw new AssertionError();}catch(IOException e){check(e==io&&chain.calls==1,"after-hook failure cannot replace original exception");}
  System.out.println("API102_INTERCEPTOR_CHECKS="+passed);
 }
}
