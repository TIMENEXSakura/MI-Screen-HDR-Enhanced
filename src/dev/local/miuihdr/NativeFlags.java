package dev.local.miuihdr;
import java.io.*;
import java.security.*;
import java.util.*;
final class NativeFlags {
 static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b&255));return s.toString();}
 static int read(RandomAccessFile m,long a)throws Exception{m.seek(a);return m.readUnsignedByte();}
 public static void main(String[] args){try{run(args);}catch(Throwable t){t.printStackTrace();System.exit(1);}}
 static void run(String[] args)throws Exception{
  String pid=args[0];long base=-1;String path=null;
  BufferedReader r=new BufferedReader(new FileReader("/proc/"+pid+"/maps"));String line;
  while((line=r.readLine())!=null){String[] f=line.trim().split("\\s+");if(f.length>=6&&f[5].endsWith("/libsurfaceflinger_common_shared.so")&&Long.parseLong(f[2],16)==0){base=Long.parseLong(f[0].split("-")[0],16);path=f[5];break;}}r.close();
  if(base<0)throw new Exception("Flag library mapping not found");
  MessageDigest d=MessageDigest.getInstance("SHA-256");FileInputStream in=new FileInputStream(path);byte[] buf=new byte[8192];int n;while((n=in.read(buf))>0)d.update(buf,0,n);in.close();
  String hash=hex(d.digest());if(!hash.equals("a7a71a9bbb91d60a3ffabdea98b8a8e599be5fd789e054dc0864e3541c61e191"))throw new Exception("Unsupported flag library "+hash);
  RandomAccessFile mem=new RandomAccessFile("/proc/"+pid+"/mem","rw");
  byte[] code=new byte[20];mem.seek(base+0x166c8);mem.readFully(code);
  if(!hex(code).equals("0824009108fddf0829a14a39080100520001090a"))throw new Exception("Unexpected flag getter code "+hex(code));
  if((read(mem,base+0x282a0)&1)!=1||(read(mem,base+0x282b0)&1)!=1)throw new Exception("Flag not initialized");
  long flag=base+0x282a8;int old=read(mem,flag);if(old!=0&&old!=1)throw new Exception("Invalid boolean");
  System.out.println("VERIFIED pid="+pid+" offset=0x282a8 old="+old);
  int value=Integer.parseInt(args[1]);if(value!=0&&value!=1)throw new Exception("Invalid value");
  mem.seek(flag);mem.writeByte(value);System.out.println("LOCAL_TONEMAP="+read(mem,flag));mem.close();
 }
}
