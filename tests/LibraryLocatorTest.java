package dev.local.miuihdr;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class LibraryLocatorTest {
 static int passed;
 static void check(boolean b,String name){if(!b)throw new AssertionError(name);System.out.println("PASS "+name);passed++;}
 static Path root()throws Exception{return Files.createTempDirectory("hdr-locator-").toRealPath();}
 static void put(Path root,String file,String contents)throws Exception{Path p=root.resolve(file.substring(1));Files.createDirectories(p.getParent());Files.writeString(p,contents);}
 static LibraryLocator.Probe probe=c->Files.readString(c.file).equals("match")?null:"clamp sequence not found";
 static void failure(LibraryLocator l,String prefix)throws Exception{
  try{l.select(probe);throw new AssertionError("Expected "+prefix);}catch(IOException e){check(e.getMessage().startsWith(prefix),prefix);}
 }
 public static void main(String[] ignored)throws Exception{
  for(String path:new String[]{"/system/lib64/libsurfaceflinger.so","/system/lib/libsurfaceflinger.so","/system_ext/lib64/libsurfaceflinger.so","/system/system_ext/lib64/libsurfaceflinger.so","/product/lib64/libsurfaceflinger.so","/vendor/lib64/libsurfaceflinger.so","/system/lib64/vndk/example/libsurfaceflinger.so"}){
   Path root=root();put(root,path,"match");LibraryLocator l=new LibraryLocator(root);l.discover(Collections.emptyList());
   LibraryLocator.Candidate c=l.select(probe);check(c.target.equals(path),"find "+path);
   check(c.payload.equals(path.startsWith("/system/")?path.substring(1):"system"+path),"mount "+path);
  }
  Path root=root();put(root,"/system_ext/lib64/libsurfaceflinger.so","stale");put(root,"/system/lib64/libsurfaceflinger.so","match");
  LibraryLocator l=new LibraryLocator(root);l.discover(List.of("/system/lib64/libsurfaceflinger.so"));check(l.select(probe).loaded,"prefer actual loaded library");
  l=new LibraryLocator(root);l.discover(List.of("/system_ext/lib64/libsurfaceflinger.so"));failure(l,"LIBRARY_UNSUPPORTED");
  l=new LibraryLocator(root);l.discover(Collections.emptyList());check(l.select(probe).target.startsWith("/system/lib64"),"fallback probes all candidates instead of stopping at first mismatch");
  put(root,"/system_ext/lib64/libsurfaceflinger.so","match");l=new LibraryLocator(root);l.discover(Collections.emptyList());failure(l,"LIBRARY_AMBIGUOUS");
  root=root();put(root,"/system_ext/lib64/libsurfaceflinger.so","match");Files.createDirectories(root.resolve("system"));Files.createSymbolicLink(root.resolve("system/system_ext"),Path.of("../system_ext"));
  l=new LibraryLocator(root);l.discover(List.of("/system/system_ext/lib64/libsurfaceflinger.so"));check(l.candidates.size()==1&&l.select(probe).target.equals("/system_ext/lib64/libsurfaceflinger.so"),"deduplicate symlink aliases");
  l=new LibraryLocator(root());l.discover(Collections.emptyList());failure(l,"LIBRARY_NOT_FOUND");
  l=new LibraryLocator(root);l.discover(List.of("/system/lib64/libsurfaceflinger.so"));failure(l,"LOADED_LIBRARY_UNAVAILABLE");
  l=new LibraryLocator(root);l.discover(List.of("/system_ext/lib64/libsurfaceflinger.so (deleted)"));failure(l,"LOADED_LIBRARY_UNAVAILABLE");
  root=root();put(root,"/apex/com.example/lib64/libsurfaceflinger.so","match");l=new LibraryLocator(root);l.discover(List.of("/apex/com.example/lib64/libsurfaceflinger.so"));failure(l,"LIBRARY_UNSUPPORTED");check(l.candidates.size()==1,"discover APEX without inventing unsupported mount layout");
  root=root();put(root,"/odm/lib64/libsurfaceflinger.so","match");l=new LibraryLocator(root);l.discover(Collections.emptyList());check(l.candidates.size()==1,"discover ODM");failure(l,"LIBRARY_UNSUPPORTED");
  check(LibraryLocator.payloadFor("/system/lib64/../../data/libsurfaceflinger.so")==null,"reject traversal");
  check(LibraryLocator.payloadFor("/system/lib64/$(touch bad)/libsurfaceflinger.so")==null,"reject shell characters");
  List<String> paths=LibraryLocator.mappedPaths("123-456 r-xp 0000 01:23 1 /system/lib64/libsurfaceflinger.so\n456-789 r-xp 0000 01:23 2 /system/lib64/libsurfaceflinger_common_shared.so\n789-aaa r--p 0000 01:23 3 /system/lib64/libsurfaceflinger.so (deleted)\n");
  check(paths.size()==3&&paths.get(2).endsWith(" (deleted)"),"parse all loaded SO candidates including alternate owners");
  root=root();put(root,"/system_root/system/lib64/libsurfaceflinger.so","match");Files.createSymbolicLink(root.resolve("system"),Path.of("system_root/system"));
  l=new LibraryLocator(root);l.discover(List.of("/system_root/system/lib64/libsurfaceflinger.so"));
  check(l.select(probe).target.equals("/system/lib64/libsurfaceflinger.so"),"use mountable system alias for system-as-root layout");
  root=root();put(root,"/system_root/system/lib64/libsurfaceflinger.so","match");Files.createDirectories(root.resolve("system"));Files.createSymbolicLink(root.resolve("system/lib64"),Path.of("../system_root/system/lib64"));
  l=new LibraryLocator(root);l.discover(Collections.emptyList());
  check(l.select(probe).target.equals("/system/lib64/libsurfaceflinger.so"),"scan a symlinked library directory and retain its mountable alias");
  root=root();put(root,"/vendor/lib64/libvendor_render_impl.so","match");put(root,"/system/lib64/libsurfaceflinger.so","stale");
  l=new LibraryLocator(root);l.discover(List.of("/vendor/lib64/libvendor_render_impl.so","/system/lib64/libsurfaceflinger.so"));
  check(l.select(probe).target.endsWith("libvendor_render_impl.so"),"resolve implementation in a renamed loaded library");
  root=root();put(root,"/system_ext/lib64/libmisurfaceflinger.so","match");l=new LibraryLocator(root);l.discover(Collections.emptyList());
  check(l.select(probe).target.endsWith("libmisurfaceflinger.so"),"discover alternate Finger candidate without maps");
  System.out.println("ALL PASS "+passed);
 }
}
