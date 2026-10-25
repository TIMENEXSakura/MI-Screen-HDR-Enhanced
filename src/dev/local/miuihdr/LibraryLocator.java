package dev.local.miuihdr;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Locates the loaded SurfaceFlinger library without a model/ROM allowlist. */
final class LibraryLocator {
    static final String NAME = "libsurfaceflinger.so";
    static final String[] PARTITIONS = {"/system", "/system_ext", "/product", "/vendor", "/odm",
        "/system/system_ext", "/system/product", "/system/vendor", "/system/odm",
        "/system_root/system", "/system_root/system_ext", "/system_root/product", "/system_root/vendor"};
    static final class Candidate {
        final Path file;
        String target, payload;
        final Set<String> aliases = new LinkedHashSet<>();
        boolean loaded;
        Candidate(Path file, String target, boolean loaded) {
            this.file=file;this.target=target;this.payload=payloadFor(target);this.loaded=loaded;aliases.add(target);
        }
    }
    interface Probe { String check(Candidate candidate) throws Exception; }
    interface Observer {
        void log(String line);
        void progress(String phase, int completed, int total);
    }
    static final class Mapping {
        final long start, end, offset, inode, deviceMajor, deviceMinor;
        final String permissions, path;
        Mapping(long start,long end,long offset,String permissions,long major,long minor,long inode,String path) {
            this.start=start;this.end=end;this.offset=offset;this.permissions=permissions;
            this.deviceMajor=major;this.deviceMinor=minor;this.inode=inode;this.path=path;
        }
        boolean matchesIdentity(long device,long fileInode) {
            long major=((device>>>8)&0xfffL)|((device>>>32)&0xfffff000L);
            long minor=(device&0xffL)|((device>>>12)&0xffffff00L);
            return inode!=0&&inode==fileInode&&deviceMajor==major&&deviceMinor==minor;
        }
        Long runtimeAddress(long fileOffset) {
            long delta=fileOffset-offset;
            return fileOffset>=offset && delta>=0 && delta<end-start ? start+delta : null;
        }
    }
    static List<Mapping> mappedSegments(String maps) {
        List<Mapping> result=new ArrayList<>();
        for(String line:maps.split("\n")) {
            String[] fields=line.trim().split("\\s+",6);
            if(fields.length!=6 || !(libraryPath(fields[5])||fields[5].endsWith(".so (deleted)")))continue;
            try {
                String[] range=fields[0].split("-",2), device=fields[3].split(":",2);
                long start=Long.parseUnsignedLong(range[0],16),end=Long.parseUnsignedLong(range[1],16);
                long offset=Long.parseUnsignedLong(fields[2],16);
                if(start<0||end<=start||offset<0||!fields[1].matches("[r-][w-][x-][ps]"))continue;
                result.add(new Mapping(start,end,offset,fields[1],Long.parseUnsignedLong(device[0],16),
                        Long.parseUnsignedLong(device[1],16),Long.parseLong(fields[4]),fields[5]));
            } catch(IllegalArgumentException|IndexOutOfBoundsException ignored) { }
        }
        return result;
    }
    final Path root;
    final List<Candidate> candidates = new ArrayList<>();
    final List<String> diagnostics = new ArrayList<>();
    private final Set<String> visited = new HashSet<>();
    private boolean loadedUnavailable;
    private final Observer observer;

    LibraryLocator(Path root) { this(root,null); }
    LibraryLocator(Path root,Observer observer) {
        this.observer=observer;
        try { this.root=root.toRealPath(); }
        catch(IOException e) {throw new IllegalArgumentException("Filesystem root unavailable",e);}
    }
    private void log(String line) { diagnostics.add(line);if(observer!=null)observer.log(line); }
    private void progress(String phase,int done,int total) { if(observer!=null)observer.progress(phase,done,total); }
    Path at(String absolute) { return root.resolve(absolute.substring(1)); }
    static String payloadFor(String target) {
        if (!target.matches("/[A-Za-z0-9_./+@-]+") || target.contains("/../") || target.contains("/./")) return null;
        if (target.matches("/system/(lib|lib64)/.+") || target.matches("/system/(system_ext|product|vendor)/(lib|lib64)/.+")) return target.substring(1);
        if (target.matches("/(system_ext|product|vendor)/(lib|lib64)/.+")) return "system"+target;
        return null; // APEX / ODM / arbitrary loaded paths are reported, not silently mis-mounted.
    }
    static boolean libraryPath(String path) {
        return path.startsWith("/") && path.substring(path.lastIndexOf('/')+1).startsWith("lib") && path.endsWith(".so");
    }
    static boolean plausibleName(String name) {
        String n=name.toLowerCase(Locale.ROOT);
        return n.endsWith(".so") && (n.contains("flinger") || n.contains("surface") || n.contains("renderengine") || n.contains("composer") || n.equals("libgui.so"));
    }
    static List<String> mappedPaths(String maps) {
        List<String> paths=new ArrayList<>();
        for(String line:maps.split("\n")) {
            String[] fields=line.trim().split("\\s+",6);
            if(fields.length==6 && (libraryPath(fields[5]) || fields[5].endsWith(".so (deleted)"))) paths.add(fields[5]);
        }
        return paths;
    }
    void add(String path, boolean loaded) {
        if(path.endsWith(" (deleted)")) {loadedUnavailable|=loaded;log("LOADED_FILE_DELETED "+path);return;}
        if(!libraryPath(path)) return;
        Path file=at(path);
        if(!Files.isRegularFile(file) || !Files.isReadable(file)) {
            if(loaded) {loadedUnavailable=true;log("LOADED_FILE_UNREADABLE "+path);}
            return;
        }
        try {
            Path real=file.toRealPath();
            // The root parameter is only for isolated filesystem fixtures.
            if(!real.startsWith(root)) {if(loaded)loadedUnavailable=true;log("OUTSIDE_ROOT "+path);return;}
            String target="/"+root.relativize(real).toString();
            for(Candidate old:candidates) if(Files.isSameFile(real,old.file)) {
                old.loaded|=loaded;old.aliases.add(path);
                if(old.payload==null&&payloadFor(path)!=null){old.target=path;old.payload=payloadFor(path);}
                return;
            }
            if(payloadFor(target)==null&&payloadFor(path)!=null)target=path;
            Candidate c=new Candidate(real,target,loaded);c.aliases.add(path);candidates.add(c);
        } catch(IOException e) {if(loaded)loadedUnavailable=true;log("READ_ERROR "+path+": "+e.getMessage());}
    }
    void directory(String path) {
        log("SEARCH "+path);
        Path dir=at(path);
        if(!Files.isDirectory(dir)) {log("SEARCH_RESULT path="+path+" status=missing");return;}
        try {
            Path realDir=dir.toRealPath();
            if(!realDir.startsWith(root))throw new IOException("library directory outside filesystem root");
            if(!visited.add(realDir.toString())) {log("SEARCH_RESULT path="+path+" real="+realDir+" status=alias_already_scanned");return;}
            // Only library directories, never user storage or a device-wide scan.
            try(Stream<Path> walk=Files.walk(realDir,4)) {
                Iterator<Path> it=walk.iterator();int count=0, before=candidates.size();
                while(it.hasNext()) {
                    Path p=it.next();if(++count>30000)throw new IOException("library directory exceeds scan limit");
                    if(plausibleName(String.valueOf(p.getFileName())))add("/"+root.relativize(dir.resolve(realDir.relativize(p))).toString(),false);
                }
                log("SEARCH_RESULT path="+path+" real="+realDir+" status=complete entries="+count+" new_candidates="+(candidates.size()-before));
            }
        } catch(IOException|UncheckedIOException e) {log("SCAN_ERROR "+path+": "+e.getMessage());}
    }
    List<Candidate> discover(List<String> mapped) {
        for(String path:mapped)add(path,true);
        List<String> directories=new ArrayList<>();
        for(String partition:PARTITIONS)for(String abi:new String[]{"lib64","lib"})directories.add(partition+"/"+abi);
        Path apex=at("/apex");
        if(Files.isDirectory(apex))try(Stream<Path> entries=Files.list(apex)) {
            Iterator<Path> it=entries.iterator();int count=0;
            while(it.hasNext() && count++<256) {
                String dir="/apex/"+it.next().getFileName();directories.add(dir+"/lib64");directories.add(dir+"/lib");
            }
        }catch(IOException e){log("SCAN_ERROR /apex: "+e.getMessage());}
        progress("discover",0,directories.size());
        for(int i=0;i<directories.size();i++) {directory(directories.get(i));progress("discover",i+1,directories.size());}
        log("DISCOVER_COMPLETE directories="+directories.size()+" candidates="+candidates.size());
        return candidates;
    }
    Candidate select(Probe probe) throws Exception {
        if(loadedUnavailable)throw new IOException("LOADED_LIBRARY_UNAVAILABLE: 已加载库不可读或已被替换，保留原库");
        if(candidates.isEmpty())throw new IOException("LIBRARY_NOT_FOUND: 常见系统目录及已加载映射中没有找到显示运行库");
        boolean hasLoaded=false;for(Candidate c:candidates)hasLoaded|=c.loaded;
        List<Candidate> matches=new ArrayList<>();List<String> errors=new ArrayList<>();
        int completed=0;progress("probe",0,candidates.size());
        for(Candidate c:candidates) {
            String size;
            try {size=Long.toString(Files.size(c.file));}catch(IOException e){size="unavailable";}
            log("CANDIDATE index="+(completed+1)+" total="+candidates.size()+" path="+c.target+" real="+c.file+
                    " aliases="+c.aliases+" loaded="+c.loaded+" size="+size+" payload="+c.payload);
            if(hasLoaded&&!c.loaded) {log("SKIP_NOT_LOADED "+c.target);progress("probe",++completed,candidates.size());continue;}
            String problem;
            if(c.payload==null)problem="MOUNT_PATH_UNSUPPORTED: 当前挂载方案不支持此位置";
            else try {problem=probe.check(c);}catch(Exception e){problem=e.toString();}
            if(problem==null) {matches.add(c);log("MATCH "+c.target+" loaded="+c.loaded);}
            else {String detail=c.target+": "+problem;errors.add(detail);log("REJECT "+detail);}
            progress("probe",++completed,candidates.size());
        }
        if(matches.size()==1)return matches.get(0);
        if(matches.size()>1)throw new IOException("LIBRARY_AMBIGUOUS: 多个库匹配，无法唯一确认挂载目标；保留原库");
        throw new IOException("LIBRARY_UNSUPPORTED: 找到了运行库，但不能生成对应补丁\n"+String.join("\n",errors.subList(0,Math.min(12,errors.size())))+(errors.size()>12?"\n其余候选见定位报告":""));
    }
}
