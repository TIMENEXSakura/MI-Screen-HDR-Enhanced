package dev.local.miuihdr;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.zip.*;

/** Generate a local install or an offline export; the APK never ships a system SO. */
public final class RootSetup {
 private static final String VERSION="1.5.9";
 private static final Pattern PATCH_OFFSET=Pattern.compile("(?:^| )file_offset=(0x[0-9a-fA-F]+)(?: |$)");
 private static final class Log implements AutoCloseable {
  private final List<String> pending=new ArrayList<>();
  private BufferedWriter writer;
  Path report;
  private int percent=-1;
  private String detail="";
  private String stageLabel="开始阶段";
  boolean exportOnly;
  void attach(Path path)throws IOException{
   report=path;writer=Files.newBufferedWriter(path,StandardCharsets.UTF_8);
   for(String line:pending){writer.write(line);writer.newLine();}pending.clear();writer.flush();
   line("DIAGNOSTICS="+path);
  }
  void line(String value){
   for(String raw:value.split("\\R",-1)){
    String description=explain(raw),line=description==null?raw:description+"\n"+raw;
    System.out.println(line);System.out.flush();
    try{if(writer==null){if(report==null)pending.add(line);}else{writer.write(line);writer.newLine();writer.flush();}}
    catch(IOException e){disableFileLog(e);}
   }
  }
  void user(String value){line("USER_LOG="+value.replaceAll("[\\r\\n]+"," ").trim());}
  void installer(String value){line("INSTALLER_LOG="+value.replaceAll("[\\r\\n]+"," "));}
  void progress(int value,String label){
   value=Math.max(percent,Math.min(100,Math.max(0,value)));
   if(value==percent&&label.equals(detail))return;
   percent=value;detail=label;line("进度说明："+label);line("PROGRESS="+value+"|"+stageLabel);
  }
  void stage(String stage){
   if(stage.equals("export")||stage.equals("export_complete")){
    boolean complete=stage.equals("export_complete");stageLabel=complete?"导出完成":"修补完成，正在导出";
    line("STAGE="+stage);line("[阶段 "+(complete?7:6)+"/7 · "+stageLabel+"] "+
      (complete?"模块 ZIP 已保存，未在本机安装。":"正在生成模块 ZIP，请稍候。"));return;
   }
   String[] keys={"prepare","discover","library","offset","patch","install","complete"};
   String[] titles={"开始阶段","扫描阶段","获取到库","获取到偏移量","开始修补","修补完成，正在刷入","刷入完成"};
   String[] descriptions={"正在检查安装文件与 Root 环境。","正在查找匹配的运行库，请稍候。",
     "已找到对应文件，正在核对库信息。","已定位需要修补的位置，地址如下。",
     "正在备份原始文件、修补并校验。","正在刷入挂载模块，请稍候。",
     "安装完成，重启设备后加载。"};
   if(exportOnly){descriptions[0]="正在检查所选文件与导出环境。";descriptions[1]="正在检查所选库的 HDR 截屏实现，请稍候。";}
   for(int i=0;i<keys.length;i++)if(keys[i].equals(stage)){
    stageLabel=titles[i];line("STAGE="+stage);line("[阶段 "+(i+1)+"/7 · "+stageLabel+"] "+descriptions[i]);return;
   }
   throw new IllegalArgumentException("Unknown installation stage "+stage);
  }
  private void disableFileLog(IOException error){
   BufferedWriter broken=writer;writer=null;
   System.out.println("USER_LOG=诊断文件写入失败，后续完整记录由应用继续保存，不会中断当前任务。路径："+report);System.out.flush();
   if(broken!=null)try{broken.close();}catch(IOException ignored){}
  }
  @Override public void close(){if(writer!=null)try{writer.close();writer=null;}catch(IOException e){disableFileLog(e);}}
 }
 private static String explain(String line){
  if(line.startsWith("COMMAND operation="))return "执行命令：下行列出操作名称、可执行文件和实际参数。";
  if(line.startsWith("COMMAND_EXIT "))return "命令已退出：exit=0 表示成功，其他值表示失败。";
  if(line.startsWith("ELF_INPUT "))return "读取二进制输入文件，下列地址仅属于文件和 ELF 空间。";
  if(line.startsWith("PATCH_ERROR"))return "此库修补检查未通过，原库保持不变；具体原因如下。";
  if(line.startsWith("LOADED_FILE_DELETED "))return "已加载库被标记为已删除，无法可靠确认目标，保留原库。";
  if(line.startsWith("LOADED_FILE_UNREADABLE "))return "已加载库当前不可读，无法可靠确认目标，保留原库。";
  if(line.startsWith("OUTSIDE_ROOT "))return "此库真实路径越出允许的文件系统根目录，已拒绝。";
  if(line.startsWith("READ_ERROR "))return "读取候选库失败，具体路径与原因如下。";
  if(line.startsWith("SCAN_ERROR "))return "此目录扫描未完成，原因如下；其余候选目录仍会继续检查。";
  if(line.startsWith("CANDIDATE "))return "读取候选库：序号/总数、目标路径、真实路径、别名、加载状态、大小和挂载路径如下。";
  if(line.startsWith("MATCH "))return "此候选库验证通过。";
  if(line.startsWith("REJECT "))return "此候选库已拒绝，具体原因如下。";
  if(line.startsWith("SKIP_NOT_LOADED "))return "跳过未被显示进程加载的候选库，优先检查实际运行实现。";
  if(line.startsWith("SEARCH "))return "正在扫描运行库目录。";
  if(line.startsWith("SEARCH_RESULT "))return "目录扫描结果：complete 已完成，missing 不存在，alias_already_scanned 已通过其他路径扫描。";
  if(line.startsWith("DISCOVER_COMPLETE "))return "目录扫描完成，已统计候选库数量。";
  if(line.startsWith("MAPPED_SEGMENT "))return "显示进程映射快照：range 为实际映射范围，file_offset 为对应文件偏移；file_identity 表示设备号和 inode 是否与当前文件一致。";
  if(line.startsWith("PATCH_RUNTIME "))return "运行时地址仅代表读取 maps 时的快照；无法验证可执行映射时显示 unavailable，不以 ELF 地址代替。";
  if(line.startsWith("MAPS_SNAPSHOT "))return "已读取 SurfaceFlinger 的运行库映射，记录进程号、快照时间和段数。";
  if(line.startsWith("MAPS_UNAVAILABLE"))return "无法读取完整进程映射，继续使用系统运行库目录定位；运行时地址可能不可用。";
  if(line.startsWith("SHA256 "))return "文件 SHA-256 校验值：下行注明用途、路径与实际读取得到的哈希。";
  if(line.startsWith("LIBRARY_SELECTED="))return "已唯一选中需要修补的库。";
  if(line.startsWith("PAYLOAD_PATH="))return "模块内挂载路径如下。";
  if(line.startsWith("SELECTED "))return "所选库的真实路径、别名、加载状态和字节大小如下。";
  if(line.startsWith("SNAPSHOT_COPY "))return "复制输入库到工作副本。";
  if(line.startsWith("SNAPSHOT_VERIFY "))return "核对输入快照哈希，确保复制期间原文件未发生变化。";
  if(line.startsWith("SELECTED_OFFSET "))return "所选实现的修补位置如下；地址来自此库的定位结果。";
  if(line.startsWith("PATCH_OPERATION "))return "正在修补截图亮度钳位和受支持的 RGB 增益算法，所有写入仅作用于工作副本。";
  if(line.startsWith("BACKUP_OPERATION "))return "恢复原始指令与受支持的原始增益算法，保存完整原库供回退使用。";
  if(line.startsWith("LIBRARY_VERIFY "))return "一致性检查：系统库应保持不变，输入及修补版均须能完整还原到同一原始备份。";
  if(line.startsWith("METADATA_COMPLETE "))return "挂载目标、模块路径、原始备份和回退校验值已写入。";
  if(line.startsWith("PACKAGE_BEGIN "))return "开始压缩模块，按条目及实际写入字节推进进度。";
  if(line.startsWith("PACKAGE_ENTRY "))return "压缩条目状态：writing 正在写入，complete 已完成；字节数如下。";
  if(line.startsWith("PACKAGE_COMPLETE "))return "压缩完成：列出文件数、输入字节总数和 ZIP 实际大小。";
  if(line.startsWith("GENERATED_ZIP="))return "已导出挂载模块。";
  if(line.startsWith("ZIP_SHA256="))return "已生成 ZIP 的 SHA-256 校验值。";
  if(line.startsWith("ROOT_INSTALLER="))return "使用当前 Root 提供者对应的安装器。";
  if(line.startsWith("INSTALL_RESULT="))return "刷入结果如下；只有安装器退出成功后才进入完成阶段。";
  if(line.startsWith("DIAGNOSTICS="))return "完整安装诊断日志将持续写入此文件。";
  if(line.startsWith("WORK_DIRECTORY_CLEANED="))return "临时工作目录已清理，导出的 ZIP 和诊断日志保留。";
  if(line.startsWith("WORK_DIRECTORY="))return "本次临时工作目录如下。";
  if(line.startsWith("TEMPLATE_BEGIN "))return "正在读取 APK 内的挂载模块模板。";
  if(line.startsWith("TEMPLATE_ENTRY "))return "模块模板文件已读取，列出相对路径和字节数。";
  if(line.startsWith("TEMPLATE_COMPLETE "))return "模块模板解包完成。";
  if(line.startsWith("IMPORT_BEGIN "))return "正在读取用户导入的原始库。";
  if(line.startsWith("OLD_MODULE_DISABLED "))return "已为旧版同功能模块保留禁用标记，避免重复启用。";
  if(line.startsWith("ROOT_")||line.startsWith("MOUNT_")||line.startsWith("METAMODULE=")||line.startsWith("VISIBLE_MOUNT=")||line.startsWith("BACKEND"))return "Root 与挂载环境信息如下；本次沿用已有挂载方式。";
  return null;
 }
 private static String run(Log log,String label,Consumer<String> observer,String... args)throws Exception{
  log.line("COMMAND operation="+label+" argv="+Arrays.toString(args));
  Process p=new ProcessBuilder(args).redirectErrorStream(true).start();StringBuilder output=new StringBuilder();
  try(BufferedReader reader=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){
   String line;while((line=reader.readLine())!=null){
    // Manager output is visible, but must not be mistaken for our progress protocol.
    if(label.equals("root_install"))log.installer(line);else log.line(line);
    output.append(line).append('\n');
    // The full stream is already on disk; only retain a bounded command result in memory.
    if(output.length()>512*1024)output.delete(0,output.length()-512*1024);
    if(observer!=null)observer.accept(line);
   }
   int exit=p.waitFor();log.line("COMMAND_EXIT operation="+label+" exit="+exit);
   if(exit!=0){
    String tail=output.substring(Math.max(0,output.length()-6000)).trim();
    if(label.equals("probe_candidate")||label.equals("probe_import")){
     // ELF details already streamed in full; keep only the final cause and PATCH_ERROR in REJECT.
     String[] lines=tail.split("\\R");List<String> cause=new ArrayList<>();
     for(int i=lines.length-1;i>=0&&cause.size()<3;i--)if(!lines[i].trim().isEmpty())cause.add(0,lines[i]);
     tail=String.join("\n",cause);
    }
    throw new IOException(label+" exit="+exit+"\n"+tail);
   }
   return output.toString().trim();
  }finally{if(p.isAlive())p.destroy();}
 }
 private static String run(Log log,String label,String... args)throws Exception{return run(log,label,null,args);}
 private static void cleanup(Path dir)throws IOException{
  try(java.util.stream.Stream<Path> walk=Files.walk(dir)){for(Path p:(Iterable<Path>)walk.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}
 }
 private static String executable(Log log,RootInstaller.Provider provider)throws Exception{
  String name=RootInstaller.name(provider);
  try{String path=run(log,"find_installer","/system/bin/sh","-c","command -v "+name);if(new File(path).canExecute())return path;}catch(IOException ignored){}
  for(String path:RootInstaller.paths(provider))if(new File(path).canExecute())return path;
  throw new IOException("ROOT_INSTALLER_MISSING: 未找到 "+provider+" 的安装器；ZIP 已保留，可在管理器中手动安装");
 }
 private static String hash(Log log,String role,Path file)throws Exception{
  String value=NativeBridge.sha256(file.toString());log.line("SHA256 role="+role+" path="+file+" value="+value);return value;
 }
 private static String field(String line,String key){
  if(line==null)return null;
  for(String item:line.split("\\s+"))if(item.startsWith(key+"="))return item.substring(key.length()+1);
  return null;
 }
 private static String valueOrUnavailable(String value){return value==null||value.equals("unavailable")?"不可用":value;}
 private static final class ProbeDetails {
  String elf,build,match,patch,rgb;
  void accept(String line){
   if(line.startsWith("ELF_INFO "))elf=line;
   else if(line.startsWith("ELF_BUILD_ID "))build=line;
   else if(line.startsWith("PATCH_MATCH "))match=line;
   else if(line.startsWith("PATCH_DETAIL "))patch=line;
   else if(line.startsWith("RGB_PATCH_DETAIL "))rgb=line;
  }
  void describeLibrary(Log log){
   String bits=field(elf,"class");
   log.user("库信息："+(bits==null?"位数不可用":bits+" 位")+"；架构 "+valueOrUnavailable(field(elf,"machine"))+"；类型 "+valueOrUnavailable(field(elf,"type"))+"。");
   String buildId=field(build,"value");
   log.user(buildId==null?"Build ID：未读取到有效构建标识。":"Build ID："+buildId);
   String method=field(match,"method");
   String description="symbol-dataflow".equals(method)?"函数符号与指令数据流":"legacy-exact".equals(method)?"精确特征码":valueOrUnavailable(method);
   log.user("匹配方式："+description+(method==null?"":"（"+method+"）")+"。");
   log.user("true".equals(field(rgb,"supported"))?"RGB 算法：库指纹已验证，将启用同帧红、绿、蓝独立增益，并备份原始算法。":"RGB 算法：此库尚不支持安全修补；仅处理已识别的亮度钳位。");
  }
 }
 private static void verifyRoundTrip(Log log,File patcher,Path snapshot,Path original,Path patched,String before,String after)throws Exception{
  if(before.equals(after)||Files.size(snapshot)!=Files.size(original)||Files.size(snapshot)!=Files.size(patched))
   throw new IOException("PATCH_VERIFY_FAILED: 修补版、备份长度或校验值异常");
  Path check=Files.createTempFile(snapshot.getParent(),"roundtrip-",".so");
  try{
   run(log,"verify_repatch",patcher.getPath(),"patch",original.toString(),check.toString());
   if(!after.equals(hash(log,"repatched_backup",check)))throw new IOException("PATCH_VERIFY_FAILED: 备份不能重建同一修补版");
   run(log,"verify_restore",patcher.getPath(),"restore",patched.toString(),check.toString());
   if(!before.equals(hash(log,"restored_patch",check)))throw new IOException("PATCH_VERIFY_FAILED: 修补版不能完整还原");
   // The input may be a valid older clamp-only patch. It must normalize to
   // exactly the same backup, rather than being accepted as an arbitrary hash.
   run(log,"verify_input",patcher.getPath(),"restore",snapshot.toString(),check.toString());
   if(!before.equals(hash(log,"restored_input",check)))throw new IOException("PATCH_VERIFY_FAILED: 输入库不能还原到已验证备份");
   log.line("PATCH_ROUNDTRIP verified=true input_normalizes_to_backup=true");
  }finally{Files.deleteIfExists(check);}
 }
 private static String briefFailure(String message){
  String first=message.split("\\R",2)[0];
  if(first.startsWith("LIBRARY_UNSUPPORTED:"))return "未找到能够安全修补的 HDR 截屏实现，原库保持不变。";
  if(first.startsWith("LIBRARY_AMBIGUOUS:"))return "匹配到多个实现，无法唯一确认目标，原库保持不变。";
  if(first.startsWith("LIBRARY_NOT_FOUND:"))return "未找到可用的显示运行库，原库保持不变。";
  if(first.startsWith("LOADED_LIBRARY_UNAVAILABLE:"))return "已加载库不可读或已被替换，已停止修补。";
  if(first.startsWith("ROOT_INSTALL_FAILED")){
   Matcher exit=Pattern.compile("root_install exit=([0-9]+)").matcher(message);
   return "Root 安装器未能完成刷入"+(exit.find()?"（退出码 "+exit.group(1)+"）":"")+"，生成的 ZIP 已保留，具体原因请查看完整日志。";
  }
  if(first.startsWith("probe_import "))return "导入库未通过实现校验，未开始修补；具体原因请查看完整日志。";
  return first.length()>220?first.substring(0,220)+"…":first;
 }
 private static String hex(long value){return "0x"+Long.toHexString(value);}
 private static final class ProcessMapping {
  final String pid;final long capturedAt;final LibraryLocator.Mapping mapping;
  ProcessMapping(String pid,long capturedAt,LibraryLocator.Mapping mapping){this.pid=pid;this.capturedAt=capturedAt;this.mapping=mapping;}
 }
 private static boolean sameMappedFile(LibraryLocator.Candidate candidate,LibraryLocator.Mapping mapping){
  if(mapping.path.endsWith(" (deleted)")||mapping.inode==0)return false;
  try{
   if(!Files.isSameFile(candidate.file,Paths.get(mapping.path)))return false;
   android.system.StructStat stat=android.system.Os.stat(candidate.file.toString());
   return mapping.matchesIdentity(stat.st_dev,stat.st_ino);
  }catch(Exception e){return false;}
 }
 private static void describeMappings(Log log,List<ProcessMapping> maps,LibraryLocator.Candidate candidate){
  int count=0;
  for(ProcessMapping item:maps){LibraryLocator.Mapping m=item.mapping;
   if(candidate.aliases.contains(m.path)||candidate.file.toString().equals(m.path)){
    count++;log.line("MAPPED_SEGMENT pid="+item.pid+" snapshot_ms="+item.capturedAt+" path="+m.path+
      " range="+hex(m.start)+"-"+hex(m.end)+" file_offset="+hex(m.offset)+" permissions="+m.permissions+
      " device="+Long.toHexString(m.deviceMajor)+":"+Long.toHexString(m.deviceMinor)+" inode="+m.inode+
      " file_identity="+(sameMappedFile(candidate,m)?"verified":"unavailable_or_changed"));
   }
  }
  if(count==0)log.line("MAPPED_SEGMENT path="+candidate.target+" status=unavailable source=proc_maps_snapshot");
 }
 private static Consumer<String> runtimeDetails(Log log,List<ProcessMapping> maps,LibraryLocator.Candidate candidate){
  return runtimeDetails(log,maps,candidate,false);
 }
 private static Consumer<String> runtimeDetails(Log log,List<ProcessMapping> maps,LibraryLocator.Candidate candidate,boolean showUser){
  return line->{
   if(!line.startsWith("PATCH_DETAIL "))return;
   Matcher match=PATCH_OFFSET.matcher(line);if(!match.find()){if(showUser)log.user("运行时地址：不可用，未读取到有效文件偏移。");return;}
   long offset;
   try{offset=Long.parseUnsignedLong(match.group(1).substring(2),16);}catch(NumberFormatException e){if(showUser)log.user("运行时地址：不可用，文件偏移格式无效。");return;}
   boolean found=false;
   for(ProcessMapping item:maps){LibraryLocator.Mapping m=item.mapping;Long address=m.runtimeAddress(offset);
    if(address==null||!m.permissions.contains("x")||!sameMappedFile(candidate,m))continue;
    found=true;log.line("PATCH_RUNTIME pid="+item.pid+" path="+m.path+" file_offset="+hex(offset)+
      " runtime_address="+hex(address)+" mapped_range="+hex(m.start)+"-"+hex(m.end)+
      " snapshot_ms="+item.capturedAt+" source=proc_maps_snapshot note=address_at_snapshot_only");
    if(showUser)log.user("运行时地址："+hex(address)+"（SurfaceFlinger PID "+item.pid+" 的映射快照，设备号和 inode 已验证）。");
   }
   if(!found){
    log.line("PATCH_RUNTIME path="+candidate.target+" file_offset="+hex(offset)+
      " status=unavailable reason=no_verified_executable_mapping source=proc_maps_snapshot");
    if(showUser)log.user("运行时地址：不可用，无法验证对应的可执行进程映射；上述 ELF 地址不代表运行时地址。");
   }
  };
 }
 private static void unpackTemplate(Log log,String apkPath,Path dir)throws Exception{
   log.line("TEMPLATE_BEGIN source="+apkPath+" entry=assets/mount-template.zip");
   try(ZipFile apk=new ZipFile(apkPath)){
    ZipEntry template=apk.getEntry("assets/mount-template.zip");if(template==null)throw new IOException("安装包缺少挂载模块模板");
    try(ZipInputStream in=new ZipInputStream(apk.getInputStream(template))){
     ZipEntry e;int count=0;long total=0;byte[] block=new byte[8192];
     while((e=in.getNextEntry())!=null){
      Path out=dir.resolve(e.getName()).normalize();if(!out.startsWith(dir)||++count>100)throw new IOException("无效模块模板");
      if(e.isDirectory()){Files.createDirectories(out);continue;}Files.createDirectories(out.getParent());long entryBytes=0;
      try(OutputStream dest=Files.newOutputStream(out)){int n;while((n=in.read(block))>0){total+=n;entryBytes+=n;if(total>4*1024*1024)throw new IOException("模块模板过大");dest.write(block,0,n);}}
      log.line("TEMPLATE_ENTRY name="+e.getName()+" bytes="+entryBytes);
     }
     log.line("TEMPLATE_COMPLETE entries="+count+" bytes="+total);
    }
   }
 }
 private static File packageModule(Log log,Path dir,Path result,boolean replace)throws Exception{
   Path temp=Files.createTempFile(result.getParent(),".mount-",".zip");
   try{
    List<Path> files;
    try(java.util.stream.Stream<Path> walk=Files.walk(dir)){files=walk.filter(Files::isRegularFile).sorted().collect(java.util.stream.Collectors.toList());}
    long total=0;for(Path file:files)total+=Files.size(file);log.line("PACKAGE_BEGIN entries="+files.size()+" input_bytes="+total);
    long written=0;int index=0,lastPercent=75;byte[] block=new byte[65536];
    try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(temp))){
     for(Path file:files){String name=dir.relativize(file).toString();long size=Files.size(file),entryBytes=0;
      log.line("PACKAGE_ENTRY index="+(++index)+" total="+files.size()+" name="+name+" bytes="+size+" status=writing");
      zip.putNextEntry(new ZipEntry(name));
      try(InputStream in=Files.newInputStream(file)){int n;while((n=in.read(block))>0){zip.write(block,0,n);written+=n;entryBytes+=n;
       int progress=75+(int)(9*written/Math.max(1,total));
       if(progress!=lastPercent){lastPercent=progress;log.progress(progress,"打包写入 "+written+"/"+total+" 字节");}
      }}
      zip.closeEntry();log.line("PACKAGE_ENTRY index="+index+" name="+name+" status=complete bytes="+entryBytes+" written_total="+written);
     }
    }
    if(replace)Files.move(temp,result,StandardCopyOption.REPLACE_EXISTING);else Files.move(temp,result);result.toFile().setReadable(true,false);
    log.line("PACKAGE_COMPLETE entries="+files.size()+" input_bytes="+written+" zip_bytes="+Files.size(result));
   }finally{Files.deleteIfExists(temp);}
   String zipHash=hash(log,"generated_zip",result);log.line("GENERATED_ZIP="+result);log.line("ZIP_SHA256="+zipHash);
   log.user("模块 ZIP 已生成："+result);
   log.progress(85,"模块 ZIP 已生成并计算 SHA-256");return result.toFile();
 }
 private static File generate(Log log,String apkPath)throws Exception{
  log.stage("prepare");log.progress(5,"准备读取本机运行库");
  String user=run(log,"current_user","/system/bin/am","get-current-user").trim();if(!user.matches("[0-9]+"))throw new IOException("无法确认下载目录所属用户");
  Path downloads=Paths.get("/storage/emulated",user,"Download","MI-Screen-HDR-Enhanced");Files.createDirectories(downloads);
  log.attach(downloads.resolve("mount-diagnostics-"+VERSION+".txt"));
  LibraryLocator locator=new LibraryLocator(Paths.get("/"),new LibraryLocator.Observer(){
   public void log(String line){log.line(line);}
   public void progress(String phase,int done,int total){
    if(phase.equals("discover"))log.progress(10+15*done/Math.max(1,total),"扫描运行库目录 "+done+"/"+total);
    else log.progress(25+35*done/Math.max(1,total),done<total?"检查运行库 "+(done+1)+"/"+total:"运行库检查完成 "+done+"/"+total);
   }
  });
  Path dir=Files.createTempDirectory(Paths.get("/data/local/tmp"),"mi-screen-hdr-");
  log.line("WORK_DIRECTORY="+dir);
  try{
   unpackTemplate(log,apkPath,dir);
   File patcher=dir.resolve("bin/hdrpatch").toFile();if(!patcher.setExecutable(true,true))throw new IOException("补丁生成器不可执行");
   log.progress(7,"模块模板已读取，检查 Root 和挂载环境");
   try{run(log,"describe_mount_backend","/system/bin/sh",dir.resolve("backend.sh").toString(),"describe");}
   catch(IOException e){log.line("ROOT_ENV_UNAVAILABLE: "+e.getMessage());}
   List<String> mapped=new ArrayList<>();List<ProcessMapping> mappings=new ArrayList<>();
   log.progress(9,"读取 SurfaceFlinger 进程映射快照");
   try{
    for(String pid:run(log,"find_surfaceflinger","/system/bin/pidof","surfaceflinger").split("\\s+"))if(pid.matches("[0-9]+")){
     try{String text=Files.readString(Paths.get("/proc",pid,"maps"));long capturedAt=System.currentTimeMillis();
      mapped.addAll(LibraryLocator.mappedPaths(text));List<LibraryLocator.Mapping> segments=LibraryLocator.mappedSegments(text);
      for(LibraryLocator.Mapping m:segments)mappings.add(new ProcessMapping(pid,capturedAt,m));
      log.line("MAPS_SNAPSHOT pid="+pid+" snapshot_ms="+capturedAt+" library_segments="+segments.size());
     }catch(IOException e){log.line("MAPS_UNAVAILABLE pid="+pid+" reason="+e.getMessage());}
    }
   }catch(Exception e){log.line("MAPS_UNAVAILABLE: falling back to library directories; "+e.getMessage());}
   log.stage("discover");log.progress(10,"准备扫描运行库目录");locator.discover(mapped);
   Map<Path,ProbeDetails> details=new HashMap<>();
   LibraryLocator.Candidate target=locator.select(candidate->{
    try{
     describeMappings(log,mappings,candidate);
     ProbeDetails info=new ProbeDetails();Consumer<String> runtime=runtimeDetails(log,mappings,candidate);
     run(log,"probe_candidate",line->{info.accept(line);runtime.accept(line);},patcher.getPath(),"probe",candidate.file.toString());
     details.put(candidate.file,info);
     return null;
    }catch(IOException e){return e.getMessage();}
   });
   log.stage("library");log.progress(60,"唯一目标已确认，复制并校验输入快照");
   log.line("LIBRARY_SELECTED="+target.target);log.line("PAYLOAD_PATH="+target.payload);
   long targetSize=Files.size(target.file);
   log.line("SELECTED real="+target.file+" aliases="+target.aliases+" loaded="+target.loaded+" size="+targetSize);
   log.user("已找到对应库："+target.target);
   if(!target.file.toString().equals(target.target))log.user("真实路径："+target.file);
   log.user("库大小："+targetSize+" 字节；"+(target.loaded?"已被显示进程加载。":"通过系统目录定位。"));
   ProbeDetails selectedInfo=details.get(target.file);
   if(selectedInfo!=null)selectedInfo.describeLibrary(log);
   else log.user("ELF 详细信息：不可用，完整定位结果已保留。");

   Path patched=dir.resolve(target.payload),original=dir.resolve("backup/original.so"),snapshot=dir.resolve("input.so");
   Files.createDirectories(patched.getParent());Files.createDirectories(original.getParent());
   String visibleBefore=hash(log,"visible_before",Paths.get(target.target));
   log.line("SNAPSHOT_COPY source="+target.file+" destination="+snapshot);
   Files.copy(target.file,snapshot);String inputHash=hash(log,"input_snapshot",snapshot);
   boolean snapshotMatches=inputHash.equals(visibleBefore);
   log.line("SNAPSHOT_VERIFY match="+snapshotMatches);
   if(!snapshotMatches)throw new IOException("LIBRARY_CHANGED: 复制期间输入库发生变化，已取消");
   log.stage("offset");
   String selectedDetail=selectedInfo==null?null:selectedInfo.patch;
   if(selectedDetail!=null){
    log.line("SELECTED_OFFSET "+selectedDetail.substring("PATCH_DETAIL ".length()));
    log.user("修补位置：文件偏移 "+valueOrUnavailable(field(selectedDetail,"file_offset"))+"；ELF 虚拟地址 "+valueOrUnavailable(field(selectedDetail,"elf_va"))+"。");
    runtimeDetails(log,mappings,target,true).accept(selectedDetail);
   }
   else{
    log.line("SELECTED_OFFSET status=unavailable detail=定位器未提供详细地址，完整匹配结果见上方候选记录");
    log.user("修补地址详情：不可用，定位器未返回完整地址；完整匹配结果已保留。");
   }
   log.progress(62,"所选库的实现定位结果已读取");
   log.stage("patch");log.progress(65,"输入快照校验通过，生成 HDR 和受支持的 RGB 修补");
   log.user("正在修补截图亮度钳位和受支持的 RGB 算法，仅修改临时副本。");
   log.line("PATCH_OPERATION action=skip_capture_brightness_clamp source="+snapshot+" output="+patched);
   String patchLog=run(log,"patch_selected",runtimeDetails(log,mappings,target),patcher.getPath(),"patch",snapshot.toString(),patched.toString());
   log.progress(67,"HDR 修补文件已生成，恢复原始库用于备份");
   log.line("BACKUP_OPERATION action=restore_original source="+snapshot+" output="+original);
   String restoreLog=run(log,"restore_original",patcher.getPath(),"restore",snapshot.toString(),original.toString());
   log.user("原始库备份已完成，正在核对修补结果与系统库的一致性。");
   log.progress(70,"原始库已备份，校验修补结果和系统库一致性");
   String before=hash(log,"original_backup",original),after=hash(log,"patched_output",patched),visible=hash(log,"visible_after",Paths.get(target.target));
   verifyRoundTrip(log,patcher,snapshot,original,patched,before,after);
   boolean stable=visible.equals(visibleBefore)&&visibleBefore.equals(hash(log,"visible_final",Paths.get(target.target)));
   log.line("LIBRARY_VERIFY visible_unchanged="+stable+" input_normalized_and_roundtrip_verified=true original_sha256="+before+" patched_sha256="+after);
   if(!stable)throw new IOException("LIBRARY_CHANGED: 生成期间系统库发生变化，已取消");
   log.user("一致性校验通过：系统库未变化，原始备份及修补版的 SHA-256 已记录。");
   Files.delete(snapshot);
   log.progress(73,"库校验通过，写入回退信息与模块元数据");
   Files.writeString(dir.resolve("original.sha256"),before+"\n");Files.writeString(dir.resolve("patched.sha256"),after+"\n");
   Files.writeString(dir.resolve("patch.txt"),patchLog+"\n"+restoreLog+"\n");
   Files.writeString(dir.resolve("target.path"),target.target+"\n");Files.writeString(dir.resolve("payload.path"),target.payload+"\n");
   Files.writeString(dir.resolve("generated.txt"),"Generated by MI Screen HDR Enhanced "+VERSION+"\ninput="+target.target+"\npayload="+target.payload+"\nloaded="+target.loaded+"\ninput_sha256="+visible+"\n");
   log.line("METADATA_COMPLETE target="+target.target+" payload="+target.payload+" backup=backup/original.so");
   log.progress(75,"开始打包已修补库和原始备份");
   return packageModule(log,dir,downloads.resolve("MI-Screen-HDR-Enhanced-mount-"+VERSION+".zip"),true);
  }finally{cleanup(dir);log.line("WORK_DIRECTORY_CLEANED="+dir);}
 }
 private static File exportImported(Log log,String apkPath,String importedPath)throws Exception{
  log.stage("prepare");log.progress(5,"准备读取所选运行库");
  String user=run(log,"current_user","/system/bin/am","get-current-user").trim();
  if(!user.matches("[0-9]+"))throw new IOException("无法确认下载目录所属用户");
  Path downloads=Paths.get("/storage/emulated",user,"Download","MI-Screen-HDR-Enhanced");Files.createDirectories(downloads);
  String stamp=java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
  log.attach(downloads.resolve("export-diagnostics-"+VERSION+"-"+stamp+".txt"));
  Path source=Paths.get(importedPath);
  if(!Files.isRegularFile(source)||Files.size(source)<64||Files.size(source)>128L*1024*1024)
   throw new IOException("IMPORT_INVALID: 原始 SO 文件大小无效");
  Path dir=Files.createTempDirectory(Paths.get("/data/local/tmp"),"mi-screen-hdr-export-");
  log.line("WORK_DIRECTORY="+dir);
  try{
   unpackTemplate(log,apkPath,dir);
   File patcher=dir.resolve("bin/hdrpatch").toFile();
   if(!patcher.setExecutable(true,true))throw new IOException("补丁生成器不可执行");
   Path snapshot=dir.resolve("input.so"),patched=dir.resolve("offline/patched.so"),original=dir.resolve("backup/original.so");
   Files.createDirectories(patched.getParent());Files.createDirectories(original.getParent());
   log.line("IMPORT_BEGIN source="+source+" size="+Files.size(source));
   String sourceHash=hash(log,"imported_source",source);
   Files.copy(source,snapshot);String snapshotHash=hash(log,"input_snapshot",snapshot);
   if(!sourceHash.equals(snapshotHash)||!sourceHash.equals(hash(log,"imported_source_after_copy",source)))
    throw new IOException("IMPORT_CHANGED: 所选文件在读取期间发生变化，已取消导出");
   log.stage("discover");log.progress(10,"正在识别所选库的截屏实现");
   ProbeDetails info=new ProbeDetails();run(log,"probe_import",info::accept,patcher.getPath(),"probe",snapshot.toString());
   log.stage("library");log.progress(60,"所选库已通过实现校验");
   log.user("已读取所选库："+Files.size(snapshot)+" 字节。");info.describeLibrary(log);
   log.stage("offset");
   if(info.patch==null)throw new IOException("IMPORT_INVALID: 未返回可验证的修补位置，已取消导出");
   log.line("SELECTED_OFFSET "+info.patch.substring("PATCH_DETAIL ".length()));
   log.user("修补位置：文件偏移 "+valueOrUnavailable(field(info.patch,"file_offset"))+"；ELF 虚拟地址 "+valueOrUnavailable(field(info.patch,"elf_va"))+"。");
   log.progress(62,"所选库的修补位置已确认");
   log.stage("patch");log.progress(65,"正在修补所选库的工作副本");
   log.user("正在修补所选文件的副本，原文件保持不变。");
   String patchLog=run(log,"patch_imported",patcher.getPath(),"patch",snapshot.toString(),patched.toString());
   log.progress(67,"修补完成，正在生成原始库备份");
   String restoreLog=run(log,"restore_imported",patcher.getPath(),"restore",snapshot.toString(),original.toString());
   String before=hash(log,"original_backup",original),after=hash(log,"patched_output",patched);
   verifyRoundTrip(log,patcher,snapshot,original,patched,before,after);
   ProbeDetails patchedInfo=new ProbeDetails(),originalInfo=new ProbeDetails();
   run(log,"verify_export_patched",patchedInfo::accept,patcher.getPath(),"probe",patched.toString());
   run(log,"verify_export_original",originalInfo::accept,patcher.getPath(),"probe",original.toString());
   if(!"patched".equals(field(patchedInfo.match,"state"))||!"original".equals(field(originalInfo.match,"state"))||
      !Objects.equals(field(info.match,"offset"),field(patchedInfo.match,"offset"))||
      !Objects.equals(field(info.match,"offset"),field(originalInfo.match,"offset")))
    throw new IOException("EXPORT_VERIFY_FAILED: 修补位置或还原状态校验失败");
   log.progress(73,"修补版和原始备份校验通过，写入模块信息");
   Files.writeString(dir.resolve("original.sha256"),before+"\n");Files.writeString(dir.resolve("patched.sha256"),after+"\n");
   Files.writeString(dir.resolve("patch.txt"),patchLog+"\n"+restoreLog+"\n");
   Files.writeString(dir.resolve("offline-export"),"1\n");
   Files.writeString(dir.resolve("generated.txt"),"Generated by MI Screen HDR Enhanced "+VERSION+"\nmode=offline-export\ninput_sha256="+snapshotHash+"\noriginal_sha256="+before+"\npatched_sha256="+after+"\ntarget=resolve_by_content_at_install\n");
   Files.delete(snapshot);
   log.user("修补与备份校验通过；刷入此包时，将在目标设备查找对应运行库。");
   log.stage("export");log.progress(75,"正在打包修补文件与原始备份");
   Path output=downloads.resolve("MI-Screen-HDR-Enhanced-mount-"+VERSION+"-export-"+before.substring(0,12)+"-"+stamp+".zip");
   return packageModule(log,dir,output,false);
  }finally{cleanup(dir);log.line("WORK_DIRECTORY_CLEANED="+dir);}
 }
 public static void main(String[] args){Log log=new Log();int exit=0;try{
  if(android.os.Process.myUid()!=0)throw new IOException("需要 Root 权限");
  if(args.length<1||args.length>3)throw new IOException("安装包参数错误");
  String action=args.length>=2?args[1]:"generate";
  if(!action.equals("generate")&&!action.equals("install")&&!action.equals("export"))throw new IOException("未知操作");
  boolean export=action.equals("export");log.exportOnly=export;
  if(export&&args.length!=3)throw new IOException("导出需要选择一个原始 SO 文件");
  if(!export&&args.length==3)throw new IOException("手动选择的 SO 仅用于导出，请使用导出操作");
  File zip=export?exportImported(log,args[0],args[2]):generate(log,args[0]);
  if(!action.equals("install")){
   log.stage("export_complete");
   log.line("PASS: 挂载模块已导出，未在本机安装。");log.user("模块已导出："+zip.getPath());
   if(log.report!=null)log.user("详细诊断："+log.report);
   log.progress(100,"模块导出完成");
  }
  else {
  RootInstaller.Provider provider=RootInstaller.identify(run(log,"identify_root_provider","/system/bin/sh","-c","su -v"));
  String installer=executable(log,provider);log.line("ROOT_INSTALLER="+provider+" "+installer);
  log.user("已选用 "+provider+" 安装器："+installer);
  log.stage("install");log.progress(85,"Root 安装器处理中，等待返回结果");
  try{run(log,"root_install",RootInstaller.command(provider,installer,zip.getPath()));log.line("INSTALL_RESULT=success exit=0");}
  catch(IOException e){log.line("INSTALL_RESULT=failed reason="+e.getMessage());throw new IOException("ROOT_INSTALL_FAILED ("+provider+"): "+e.getMessage()+"\nZIP 已保留："+zip,e);}
  log.progress(98,"Root 安装成功，完成当前启动配置");
  try{run(log,"current_boot_properties","/system/bin/sh","-c","PATH=/system/bin:/system/xbin:/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk; resetprop -n debug.sf.local_tonemap_screenshots true && resetprop -n debug.sf.true_hdr_screenshots true");}
  catch(Exception e){log.line("CURRENT_BOOT: 属性等待下次启动设置："+e.getMessage());log.user("截图属性将于下次启动设置；挂载模块已安装。");}
  log.line("CURRENT_BOOT: 显示服务缓存由下次启动刷新，不写入进程内存。");
  for(String root:new String[]{"/data/adb/modules","/data/adb/modules_update"}){
   File old=new File(root,"hdr_screenshot_tonemap");if(new File(old,"module.prop").isFile()){
    boolean created=new File(old,"disable").createNewFile();log.line("OLD_MODULE_DISABLED path="+old+" marker_created="+created);
   }
  }
  log.stage("complete");log.line("PASS: 挂载模块已安装，原库已备份；下次启动验证。未重启显示服务。");
  log.user("挂载模块已安装，原库已备份；下次启动验证生效，本次未重启显示服务。");
  if(log.report!=null)log.user("Root 详细诊断："+log.report);
  log.progress(100,"挂载模块安装完成");
  }
 }catch(Throwable t){
  exit=1;String message=t.getMessage()==null?t.toString():t.getMessage();
  try{
   log.line("ERROR "+message);log.user("未完成："+briefFailure(message));
   if(log.report!=null){log.line("定位报告："+log.report);log.user("Root 详细诊断："+log.report);}
  }catch(Throwable ignored){System.err.println(message);}
 }finally{log.close();}
 if(exit!=0)System.exit(exit);
 }
}
