package dev.local.miuihdr;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.zip.*;

/** The APK generates a device-local mount ZIP; it never ships a system SO. */
public final class RootSetup {
 private static final String VERSION="1.5.1";
 private static final Pattern PATCH_OFFSET=Pattern.compile("(?:^| )file_offset=(0x[0-9a-fA-F]+)(?: |$)");
 private static final class Log implements AutoCloseable {
  private final List<String> pending=new ArrayList<>();
  private BufferedWriter writer;
  Path report;
  private int percent=-1;
  private String detail="";
  private String stageLabel="开始阶段";
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
  void progress(int value,String label){
   value=Math.max(percent,Math.min(100,Math.max(0,value)));
   if(value==percent&&label.equals(detail))return;
   percent=value;detail=label;line("进度说明："+label);line("PROGRESS="+value+"|"+stageLabel);
  }
  void stage(String stage){
   String[] keys={"prepare","discover","library","offset","patch","install","complete"};
   String[] titles={"开始阶段","扫描阶段","获取到库","获取到偏移量","开始修补","修补完成，正在刷入","刷入完成"};
   String[] descriptions={"读取安装包模板、Root 环境和显示进程映射。","检查已加载运行库及系统目录，逐个验证实现。",
     "已唯一确认需要修补的本机运行库，接下来核对输入快照。","展示所选库实现的文件偏移、ELF 地址和可验证的进程映射地址。",
     "仅在临时副本中生成补丁和原始备份，校验后打包。","模块 ZIP 已生成并记录校验值，等待 Root 安装器完成刷入。",
     "Root 安装器已成功退出，当前启动配置处理完成。"};
   for(int i=0;i<keys.length;i++)if(keys[i].equals(stage)){
    stageLabel=titles[i];line("STAGE="+stage);line("[阶段 "+(i+1)+"/7 · "+stageLabel+"] "+descriptions[i]);return;
   }
   throw new IllegalArgumentException("Unknown installation stage "+stage);
  }
  private void disableFileLog(IOException error){
   BufferedWriter broken=writer;writer=null;
   System.out.println("日志提示：诊断文件写入失败，后续日志仍实时显示在安装界面；不会中断刷入。路径："+report+"；原因："+error.getMessage());System.out.flush();
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
  if(line.startsWith("PATCH_OPERATION "))return "正在修改截图亮度钳位分支，所有写入仅作用于工作目录中的副本。";
  if(line.startsWith("BACKUP_OPERATION "))return "恢复原始指令并保存原库备份，供安装回退使用。";
  if(line.startsWith("LIBRARY_VERIFY "))return "一致性检查：系统库应保持不变，且其哈希应等于原始备份或对应的修补版。";
  if(line.startsWith("METADATA_COMPLETE "))return "挂载目标、模块路径、原始备份和回退校验值已写入。";
  if(line.startsWith("PACKAGE_BEGIN "))return "开始压缩模块，按条目及实际写入字节推进进度。";
  if(line.startsWith("PACKAGE_ENTRY "))return "压缩条目状态：writing 正在写入，complete 已完成；字节数如下。";
  if(line.startsWith("PACKAGE_COMPLETE "))return "压缩完成：列出文件数、输入字节总数和 ZIP 实际大小。";
  if(line.startsWith("GENERATED_ZIP="))return "已导出本机挂载模块。";
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
  if(line.startsWith("IMPORT_COMPARE "))return "比较导入库和本机库恢复后的原始哈希，必须一致才可使用。";
  if(line.startsWith("OLD_MODULE_DISABLED "))return "已为旧版同功能模块保留禁用标记，避免重复启用。";
  if(line.startsWith("ROOT_")||line.startsWith("MOUNT_")||line.startsWith("METAMODULE=")||line.startsWith("VISIBLE_MOUNT=")||line.startsWith("BACKEND"))return "Root 与挂载环境信息如下；本次沿用已有挂载方式。";
  return null;
 }
 private static String run(Log log,String label,Consumer<String> observer,String... args)throws Exception{
  log.line("COMMAND operation="+label+" argv="+Arrays.toString(args));
  Process p=new ProcessBuilder(args).redirectErrorStream(true).start();StringBuilder output=new StringBuilder();
  try(BufferedReader reader=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){
   String line;while((line=reader.readLine())!=null){
    log.line(line);output.append(line).append('\n');
    // The full stream is already on disk; only retain a bounded command result in memory.
    if(output.length()>512*1024)output.delete(0,output.length()-512*1024);
    if(observer!=null)observer.accept(line);
   }
   int exit=p.waitFor();log.line("COMMAND_EXIT operation="+label+" exit="+exit);
   if(exit!=0){String tail=output.substring(Math.max(0,output.length()-6000));throw new IOException(label+" exit="+exit+"\n"+tail.trim());}
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
  return line->{
   if(!line.startsWith("PATCH_DETAIL "))return;
   Matcher match=PATCH_OFFSET.matcher(line);if(!match.find())return;
   long offset;
   try{offset=Long.parseUnsignedLong(match.group(1).substring(2),16);}catch(NumberFormatException e){return;}
   boolean found=false;
   for(ProcessMapping item:maps){LibraryLocator.Mapping m=item.mapping;Long address=m.runtimeAddress(offset);
    if(address==null||!m.permissions.contains("x")||!sameMappedFile(candidate,m))continue;
    found=true;log.line("PATCH_RUNTIME pid="+item.pid+" path="+m.path+" file_offset="+hex(offset)+
      " runtime_address="+hex(address)+" mapped_range="+hex(m.start)+"-"+hex(m.end)+
      " snapshot_ms="+item.capturedAt+" source=proc_maps_snapshot note=address_at_snapshot_only");
   }
   if(!found)log.line("PATCH_RUNTIME path="+candidate.target+" file_offset="+hex(offset)+
     " status=unavailable reason=no_verified_executable_mapping source=proc_maps_snapshot");
  };
 }
 private static File generate(Log log,String apkPath,String importedPath)throws Exception{
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
   File patcher=dir.resolve("bin/hdrpatch").toFile();if(!patcher.setExecutable(true,true))throw new IOException("补丁生成器不可执行");
   log.progress(7,"模块模板已读取，检查 Root 和挂载环境");
   try{run(log,"describe_mount_backend","/system/bin/sh",dir.resolve("backend.sh").toString(),"describe");}
   catch(IOException e){log.line("ROOT_ENV_UNAVAILABLE: "+e.getMessage());}
   Path imported=null;
   if(importedPath!=null){
    Path source=Paths.get(importedPath);long length=Files.size(source);
    if(!Files.isRegularFile(source)||length<64||length>128L*1024*1024)throw new IOException("IMPORT_INVALID: 原始 SO 文件大小无效");
    imported=dir.resolve("import.so");log.line("IMPORT_BEGIN source="+source+" size="+length);Files.copy(source,imported);
    hash(log,"imported_so",imported);run(log,"probe_import",patcher.getPath(),"probe",imported.toString());
   }
   final Path input=imported;
   final String importHash=input==null?null:NativeBridge.sha256(input.toString());
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
   Map<Path,String> details=new HashMap<>();
   LibraryLocator.Candidate target=locator.select(candidate->{
    try{
     describeMappings(log,mappings,candidate);
     String probeResult=run(log,"probe_candidate",runtimeDetails(log,mappings,candidate),patcher.getPath(),"probe",candidate.file.toString());
     for(String line:probeResult.split("\n"))if(line.startsWith("PATCH_DETAIL "))details.put(candidate.file,line);
     if(input!=null&&!importHash.equals(hash(log,"candidate_import_match",candidate.file))){
      Path restored=dir.resolve("candidate-original.so");
      try{run(log,"restore_candidate_for_import_check",patcher.getPath(),"restore",candidate.file.toString(),restored.toString());
       String restoredHash=hash(log,"candidate_restored_original",restored);
       log.line("IMPORT_COMPARE expected="+importHash+" actual="+restoredHash+" match="+importHash.equals(restoredHash));
       if(!importHash.equals(restoredHash))return "IMPORT_MISMATCH: 导入文件不是本机此库的原版";
      }finally{Files.deleteIfExists(restored);}
     }
     return null;
    }catch(IOException e){return e.getMessage();}
   });
   log.stage("library");log.progress(60,"唯一目标已确认，复制并校验输入快照");
   log.line("LIBRARY_SELECTED="+target.target);log.line("PAYLOAD_PATH="+target.payload);
   log.line("SELECTED real="+target.file+" aliases="+target.aliases+" loaded="+target.loaded+" size="+Files.size(target.file));

   Path patched=dir.resolve(target.payload),original=dir.resolve("backup/original.so"),snapshot=dir.resolve("input.so");
   Files.createDirectories(patched.getParent());Files.createDirectories(original.getParent());
   String visibleBefore=hash(log,"visible_before",Paths.get(target.target));
   log.line("SNAPSHOT_COPY source="+(input==null?target.file:input)+" destination="+snapshot);
   Files.copy(input==null?target.file:input,snapshot);String inputHash=hash(log,"input_snapshot",snapshot);
   boolean snapshotMatches=inputHash.equals(input==null?visibleBefore:importHash);
   log.line("SNAPSHOT_VERIFY match="+snapshotMatches);
   if(!snapshotMatches)throw new IOException("LIBRARY_CHANGED: 复制期间输入库发生变化，已取消");
   log.stage("offset");
   String selectedDetail=details.get(target.file);
   if(selectedDetail!=null){
    log.line("SELECTED_OFFSET "+selectedDetail.substring("PATCH_DETAIL ".length()));
    runtimeDetails(log,mappings,target).accept(selectedDetail);
   }
   else log.line("SELECTED_OFFSET status=unavailable detail=定位器未提供详细地址，完整匹配结果见上方候选记录");
   log.progress(62,"所选库的实现定位结果已读取");
   log.stage("patch");log.progress(65,"输入快照校验通过，生成 HDR 钳位修补");
   log.line("PATCH_OPERATION action=skip_capture_brightness_clamp source="+snapshot+" output="+patched);
   String patchLog=run(log,"patch_selected",runtimeDetails(log,mappings,target),patcher.getPath(),"patch",snapshot.toString(),patched.toString());
   log.progress(67,"HDR 修补文件已生成，恢复原始库用于备份");
   log.line("BACKUP_OPERATION action=restore_original source="+snapshot+" output="+original);
   String restoreLog=run(log,"restore_original",patcher.getPath(),"restore",snapshot.toString(),original.toString());
   log.progress(70,"原始库已备份，校验修补结果和系统库一致性");
   String before=hash(log,"original_backup",original),after=hash(log,"patched_output",patched),visible=hash(log,"visible_after",Paths.get(target.target));
   boolean stable=visible.equals(visibleBefore),known=visible.equals(before)||visible.equals(after);
   log.line("LIBRARY_VERIFY visible_unchanged="+stable+" visible_matches_backup_or_patched="+known+" original_sha256="+before+" patched_sha256="+after);
   if(!stable||!known)throw new IOException("LIBRARY_CHANGED: 生成期间系统库发生变化，已取消");
   Files.delete(snapshot);if(imported!=null)Files.delete(imported);
   log.progress(73,"库校验通过，写入回退信息与模块元数据");
   Files.writeString(dir.resolve("original.sha256"),before+"\n");Files.writeString(dir.resolve("patched.sha256"),after+"\n");
   Files.writeString(dir.resolve("patch.txt"),patchLog+"\n"+restoreLog+"\n");
   Files.writeString(dir.resolve("target.path"),target.target+"\n");Files.writeString(dir.resolve("payload.path"),target.payload+"\n");
   Files.writeString(dir.resolve("generated.txt"),"Generated by MI Screen HDR Enhanced "+VERSION+"\ninput="+target.target+"\npayload="+target.payload+"\nloaded="+target.loaded+"\ninput_sha256="+visible+"\n");
   log.line("METADATA_COMPLETE target="+target.target+" payload="+target.payload+" backup=backup/original.so");
   log.progress(75,"开始打包已修补库和原始备份");
   Path result=downloads.resolve("MI-Screen-HDR-Enhanced-mount-"+VERSION+".zip"),temp=Files.createTempFile(downloads,".mount-",".zip");
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
    Files.move(temp,result,StandardCopyOption.REPLACE_EXISTING);result.toFile().setReadable(true,false);
    log.line("PACKAGE_COMPLETE entries="+files.size()+" input_bytes="+written+" zip_bytes="+Files.size(result));
   }finally{Files.deleteIfExists(temp);}
   String zipHash=hash(log,"generated_zip",result);log.line("GENERATED_ZIP="+result);log.line("ZIP_SHA256="+zipHash);
   log.progress(85,"模块 ZIP 已生成并计算 SHA-256");return result.toFile();
  }finally{cleanup(dir);log.line("WORK_DIRECTORY_CLEANED="+dir);}
 }
 public static void main(String[] args){Log log=new Log();int exit=0;try{
  if(android.os.Process.myUid()!=0)throw new IOException("需要 Root 权限");
  if(args.length<1||args.length>3)throw new IOException("安装包参数错误");
  String action=args.length>=2?args[1]:"generate";
  if(!action.equals("generate")&&!action.equals("install"))throw new IOException("未知操作");
  File zip=generate(log,args[0],args.length==3?args[2]:null);
  if(action.equals("generate")){log.line("PASS: 挂载模块已生成，尚未安装。");}
  else {
  RootInstaller.Provider provider=RootInstaller.identify(run(log,"identify_root_provider","/system/bin/sh","-c","su -v"));
  String installer=executable(log,provider);log.line("ROOT_INSTALLER="+provider+" "+installer);
  log.stage("install");log.progress(85,"Root 安装器处理中，等待返回结果");
  try{run(log,"root_install",RootInstaller.command(provider,installer,zip.getPath()));log.line("INSTALL_RESULT=success exit=0");}
  catch(IOException e){log.line("INSTALL_RESULT=failed reason="+e.getMessage());throw new IOException("ROOT_INSTALL_FAILED ("+provider+"): "+e.getMessage()+"\nZIP 已保留："+zip,e);}
  log.progress(98,"Root 安装成功，完成当前启动配置");
  try{run(log,"current_boot_properties","/system/bin/sh","-c","PATH=/system/bin:/system/xbin:/data/adb/ksu/bin:/data/adb/ap/bin:/data/adb/magisk; resetprop -n debug.sf.local_tonemap_screenshots true && resetprop -n debug.sf.true_hdr_screenshots true");}
  catch(Exception e){log.line("CURRENT_BOOT: 属性等待下次启动设置："+e.getMessage());}
  log.line("CURRENT_BOOT: 显示服务缓存由下次启动刷新，不写入进程内存。");
  for(String root:new String[]{"/data/adb/modules","/data/adb/modules_update"}){
   File old=new File(root,"hdr_screenshot_tonemap");if(new File(old,"module.prop").isFile()){
    boolean created=new File(old,"disable").createNewFile();log.line("OLD_MODULE_DISABLED path="+old+" marker_created="+created);
   }
  }
  log.stage("complete");log.line("PASS: 挂载模块已安装，原库已备份；下次启动验证。未重启显示服务。");log.progress(100,"挂载模块安装完成");
  }
 }catch(Throwable t){
  exit=1;String message=t.getMessage()==null?t.toString():t.getMessage();
  try{log.line("ERROR "+message);if(log.report!=null)log.line("定位报告："+log.report);}catch(Throwable ignored){System.err.println(message);}
 }finally{log.close();}
 if(exit!=0)System.exit(exit);
 }
}
