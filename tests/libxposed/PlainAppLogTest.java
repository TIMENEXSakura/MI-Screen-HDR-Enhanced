package dev.local.miuihdr;

import java.nio.file.Files;
import java.nio.file.Path;

/** This process has neither libxposed nor the module entry on its classpath. */
public final class PlainAppLogTest {
 public static void main(String[] args)throws Exception{
  for(String name:new String[]{"io.github.libxposed.api.XposedModule","dev.local.miuihdr.MiuiHook"})try{
   Class.forName(name);throw new AssertionError("test must run without "+name);
  }catch(ClassNotFoundException expected){}
  HdrLog.log("settings/root diagnostic");
  if(android.util.Log.calls!=1)throw new AssertionError("independent logger did not run");
  Path input=Files.createTempFile("miuihdr-log-test", ".txt");
  try{
   Files.writeString(input,"abc");
   if(!NativeBridge.sha256(input.toString()).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))throw new AssertionError("root checksum helper unavailable");
  }finally{Files.delete(input);}
  System.out.println("PASS: ordinary app/root logging and checksum run without libxposed or MiuiHook");
 }
}
