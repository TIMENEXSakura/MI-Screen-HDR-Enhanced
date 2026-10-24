package dev.local.miuihdr;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** The APK generates a device-local mount ZIP; it never ships a system SO. */
public final class RootSetup {
 private static final String VERSION="1.4.5";
 private static String run(String... args)throws Exception{
  Process p=new ProcessBuilder(args).redirectErrorStream(true).start();ByteArrayOutputStream out=new ByteArrayOutputStream();
  try(InputStream in=p.getInputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}
  String s=out.toString("UTF-8");if(p.waitFor()!=0)throw new IOException(s.trim());return s.trim();
 }
 private static void cleanup(Path dir)throws IOException{
  try(java.util.stream.Stream<Path> walk=Files.walk(dir)){for(Path p:(Iterable<Path>)walk.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}
 }
 private static String executable(RootInstaller.Provider provider)throws Exception{
  String name=RootInstaller.name(provider);
  try{String path=run("/system/bin/sh","-c","command -v "+name);if(new File(path).canExecute())return path;}catch(IOException ignored){}
  for(String path:RootInstaller.paths(provider))if(new File(path).canExecute())return path;
  throw new IOException("ROOT_INSTALLER_MISSING: 未找到 "+provider+" 的安装器；ZIP 已保留，可在管理器中手动安装");
 }
 private static void installReport(File zip,String message){
  try{Files.writeString(zip.toPath().getParent().resolve("mount-diagnostics-"+VERSION+".txt"),"\n"+message+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
  catch(IOException ignored){}
 }
 private static File generate(String apkPath)throws Exception{
  String user=run("/system/bin/am","get-current-user").trim();if(!user.matches("[0-9]+"))throw new IOException("无法确认下载目录所属用户");
  Path downloads=Paths.get("/storage/emulated",user,"Download","MI-Screen-HDR-Enhanced");Files.createDirectories(downloads);
  Path report=downloads.resolve("mount-diagnostics-"+VERSION+".txt");
  LibraryLocator locator=new LibraryLocator(Paths.get("/"));
  Path dir=Files.createTempDirectory(Paths.get("/data/local/tmp"),"mi-screen-hdr-");
  try{
   try(ZipFile apk=new ZipFile(apkPath);ZipInputStream in=new ZipInputStream(apk.getInputStream(apk.getEntry("assets/mount-template.zip")))){
    ZipEntry e;int count=0;long total=0;byte[] block=new byte[8192];
    while((e=in.getNextEntry())!=null){
     Path out=dir.resolve(e.getName()).normalize();if(!out.startsWith(dir)||++count>100)throw new IOException("无效模块模板");
     if(e.isDirectory()){Files.createDirectories(out);continue;}Files.createDirectories(out.getParent());
     try(OutputStream dest=Files.newOutputStream(out)){int n;while((n=in.read(block))>0){total+=n;if(total>4*1024*1024)throw new IOException("模块模板过大");dest.write(block,0,n);}}
    }
   }
   File patcher=dir.resolve("bin/hdrpatch").toFile();if(!patcher.setExecutable(true,true))throw new IOException("补丁生成器不可执行");
   try{locator.diagnostics.add(run("/system/bin/sh",dir.resolve("backend.sh").toString(),"describe"));}
   catch(IOException e){locator.diagnostics.add("ROOT_ENV_UNAVAILABLE: "+e.getMessage());}
   List<String> mapped=new ArrayList<>();
   try{
    for(String pid:run("/system/bin/pidof","surfaceflinger").split("\\s+"))if(pid.matches("[0-9]+")){
     try{mapped.addAll(LibraryLocator.mappedPaths(Files.readString(Paths.get("/proc",pid,"maps"))));}
     catch(IOException e){locator.diagnostics.add("MAPS_UNAVAILABLE pid="+pid);}
    }
   }catch(Exception e){locator.diagnostics.add("MAPS_UNAVAILABLE: falling back to library directories");}
   locator.discover(mapped);
   LibraryLocator.Candidate target=locator.select(candidate->{
    try{String match=run(patcher.getPath(),"probe",candidate.file.toString());locator.diagnostics.add(match+" path="+candidate.target);return null;}
    catch(IOException e){return e.getMessage();}
   });
   System.out.println("LIBRARY_SELECTED="+target.target);System.out.println("PAYLOAD_PATH="+target.payload);
   Path patched=dir.resolve(target.payload),original=dir.resolve("backup/original.so"),snapshot=dir.resolve("input.so");
   Files.createDirectories(patched.getParent());Files.createDirectories(original.getParent());Files.copy(target.file,snapshot);
   String inputHash=NativeBridge.sha256(snapshot.toString());
   String log=run(patcher.getPath(),"patch",snapshot.toString(),patched.toString())+"\n"+run(patcher.getPath(),"restore",snapshot.toString(),original.toString())+"\n";
   String before=NativeBridge.sha256(original.toString()),after=NativeBridge.sha256(patched.toString()),visible=NativeBridge.sha256(target.target);
   if(!visible.equals(inputHash)||(!visible.equals(before)&&!visible.equals(after)))throw new IOException("LIBRARY_CHANGED: 生成期间系统库发生变化，已取消");
   Files.delete(snapshot);
   Files.writeString(dir.resolve("original.sha256"),before+"\n");Files.writeString(dir.resolve("patched.sha256"),after+"\n");Files.writeString(dir.resolve("patch.txt"),log);
   Files.writeString(dir.resolve("target.path"),target.target+"\n");Files.writeString(dir.resolve("payload.path"),target.payload+"\n");
   Files.writeString(dir.resolve("generated.txt"),"Generated by MI Screen HDR Enhanced "+VERSION+"\ninput="+target.target+"\npayload="+target.payload+"\nloaded="+target.loaded+"\ninput_sha256="+visible+"\n");
   locator.diagnostics.add("SELECTED "+target.target+" -> "+target.payload+" sha256="+visible);
   Files.writeString(report,String.join("\n",locator.diagnostics)+"\n");
   // Export to the active Android user's download directory (including user 999).
   Path result=downloads.resolve("MI-Screen-HDR-Enhanced-mount-"+VERSION+".zip"),temp=Files.createTempFile(downloads,".mount-",".zip");
   try{
    try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(temp));java.util.stream.Stream<Path> walk=Files.walk(dir)){
     for(Path p:(Iterable<Path>)walk.filter(Files::isRegularFile).sorted()::iterator){ZipEntry entry=new ZipEntry(dir.relativize(p).toString());zip.putNextEntry(entry);Files.copy(p,zip);zip.closeEntry();}
    }
    Files.move(temp,result,StandardCopyOption.REPLACE_EXISTING);result.toFile().setReadable(true,false);
   }finally{Files.deleteIfExists(temp);}
   System.out.println(log.trim());System.out.println("GENERATED_ZIP="+result);System.out.println("ZIP_SHA256="+NativeBridge.sha256(result.toString()));
   return result.toFile();
  }catch(Exception e){
   locator.diagnostics.add("ERROR "+e.getMessage());
   try{Files.writeString(report,String.join("\n",locator.diagnostics)+"\n");}catch(IOException ignored){}
   throw new IOException(e.getMessage()+"\n定位报告："+report,e);
  }finally{cleanup(dir);}
 }
 public static void main(String[] args){try{
  if(android.os.Process.myUid()!=0)throw new IOException("需要 Root 权限");
  if(args.length<1||args.length>2)throw new IOException("安装包参数错误");
  String action=args.length==2?args[1]:"generate";
  if(!action.equals("generate")&&!action.equals("install"))throw new IOException("未知操作");
  File zip=generate(args[0]);if(action.equals("generate")){System.out.println("PASS: 挂载模块已生成，尚未安装。");return;}
  RootInstaller.Provider provider=RootInstaller.identify(run("/system/bin/sh","-c","su -v"));
  String installer=executable(provider);System.out.println("ROOT_INSTALLER="+provider+" "+installer);
  installReport(zip,"ROOT_INSTALLER="+provider+" "+installer);
  try{String result=run(RootInstaller.command(provider,installer,zip.getPath()));System.out.println(result);installReport(zip,"INSTALL_RESULT=success\n"+result);}
  catch(IOException e){installReport(zip,"INSTALL_RESULT=failed\n"+e.getMessage());throw new IOException("ROOT_INSTALL_FAILED ("+provider+"): "+e.getMessage()+"\nZIP 已保留："+zip,e);}
  try{System.out.println(run("/system/bin/sh","-c","PATH=/system/bin:/system/xbin:/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk; resetprop -n debug.sf.local_tonemap_screenshots true && resetprop -n debug.sf.true_hdr_screenshots true"));}
  catch(Exception e){System.out.println("CURRENT_BOOT: 属性等待下次启动设置："+e.getMessage());}
  try{String pid=run("/system/bin/pidof","surfaceflinger");if(!pid.matches("[0-9]+"))throw new IOException("无法确认显示服务");NativeFlags.run(new String[]{pid,"1"});}
  catch(Exception e){System.out.println("CURRENT_BOOT: 等待下次启动更新缓存开关："+e.getMessage());}
  for(String root:new String[]{"/data/adb/modules","/data/adb/modules_update"}){
   File old=new File(root,"hdr_screenshot_tonemap");if(new File(old,"module.prop").isFile())new File(old,"disable").createNewFile();
  }
  System.out.println("PASS: 挂载模块已安装，原库已备份；下次启动验证。未重启显示服务。");
 }catch(Throwable t){System.err.println(t.getMessage()==null?t.toString():t.getMessage());System.exit(1);}}
}
