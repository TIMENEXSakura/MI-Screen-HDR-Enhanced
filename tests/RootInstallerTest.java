package dev.local.miuihdr;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class RootInstallerTest {
    static int count;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);count++;System.out.println("PASS "+message);}
    interface Action { void run(String[] args)throws Exception; }
    static final class Fixture implements AutoCloseable {
        final Path base=Files.createTempDirectory("hdr-installer-test-"),adb=base.resolve("adb"),zip=base.resolve("HDR $(literal) test.zip");
        final String generation=UUID.randomUUID().toString();
        final List<List<String>> calls=new ArrayList<>();
        final List<String> reports=new ArrayList<>();
        Fixture()throws Exception { writeZip(generation); }
        void writeZip(String identity)throws Exception {
            try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry("install.id"));out.write(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();
            }
        }
        Path module(String root,String identity)throws Exception {
            Path path=adb.resolve(root).resolve(RootInstaller.MODULE_ID);Files.createDirectories(path);
            Files.writeString(path.resolve("module.prop"),"id="+RootInstaller.MODULE_ID+"\n");
            if(identity!=null)Files.writeString(path.resolve("install.id"),identity+"\n");
            Files.createFile(path.resolve("disable"));return path;
        }
        void install(RootInstaller.Provider provider,Action action)throws IOException {
            RootInstaller.installAndEnable(provider,"/provider daemon",zip,adb,args->{calls.add(Arrays.asList(args));action.run(args);return "mock success";},reports::add);
        }
        void success(RootInstaller.Provider provider)throws IOException {
            install(provider,args->{if(isEnable(args))Files.deleteIfExists(adb.resolve("modules").resolve(RootInstaller.MODULE_ID).resolve("disable"));});
        }
        @Override public void close()throws Exception {
            try(java.util.stream.Stream<Path> paths=Files.walk(base)) {
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);
            }
        }
    }
    static boolean isEnable(String[] args){return args.length==4&&args[1].equals("module")&&args[2].equals("enable");}
    static void fails(Fixture f,RootInstaller.Provider provider,Action action,String prefix)throws Exception {
        try{f.install(provider,action);throw new AssertionError("operation should fail");}
        catch(IOException e){check(e.getMessage().startsWith(prefix),"failure correctly reports "+prefix);}
    }
    public static void main(String[] ignored)throws Exception {
        for(String name:new String[]{"4.2.1:KernelSU","3.0:SukiSU","2.0:YukiSU"})
            check(RootInstaller.identify(name)==RootInstaller.Provider.KERNELSU,"provider "+name);
        for(String name:new String[]{"11219:APatch","0.11.3:KernelPatch"})
            check(RootInstaller.identify(name)==RootInstaller.Provider.APATCH,"provider "+name);
        check(RootInstaller.identify("30.0:MAGISKSU")==RootInstaller.Provider.MAGISK,"Magisk provider");
        for(String name:new String[]{"","su from util-linux 2.40","unknown-root"}) {
            try{RootInstaller.identify(name);throw new AssertionError("unknown provider accepted");}
            catch(IOException e){check(e.getMessage().startsWith("ROOT_PROVIDER_UNKNOWN"),"unknown provider keeps manual-install fallback");}
        }
        String zip="/storage/emulated/0/Download/HDR test $(data).zip";
        for(RootInstaller.Provider p:RootInstaller.Provider.values()) {
            String[] command=RootInstaller.command(p,"/installer",zip);
            check(command[command.length-1].equals(zip),p+" ZIP remains a single literal argument");
            check(Arrays.equals(command,p==RootInstaller.Provider.MAGISK?new String[]{"/installer","--install-module",zip}:new String[]{"/installer","module","install",zip}),p+" install command");
        }
        check(RootInstaller.paths(RootInstaller.Provider.APATCH)[0].equals("/data/adb/apd"),"official APatch daemon location");
        for(RootInstaller.Provider provider:RootInstaller.Provider.values()) {
            try(Fixture f=new Fixture()) {
                Path live=f.module("modules",UUID.randomUUID().toString()),staged=f.module("modules_update",f.generation);
                for(String marker:new String[]{"boot-pending","rollback.txt","skip_mount","skip_mountify"})Files.writeString(live.resolve(marker),"old recovery");
                Files.createFile(staged.resolve("skip_mount"));Files.createFile(staged.resolve("skip_mountify"));
                f.success(provider);
                check(!Files.exists(live.resolve("disable"))&&!Files.exists(staged.resolve("disable")),provider+" installed update automatically enabled in both directories");
                check(f.calls.size()==(provider==RootInstaller.Provider.MAGISK?1:2),provider+" uses its supported enable mechanism");
                if(provider!=RootInstaller.Provider.MAGISK)check(f.calls.get(1).equals(Arrays.asList("/provider daemon","module","enable",RootInstaller.MODULE_ID)),provider+" daemon receives exact module id");
                check(f.calls.get(0).get(f.calls.get(0).size()-1).equals(f.zip.toString()),provider+" installation ZIP remains literal");
                check(f.reports.stream().anyMatch(r->r.contains("ENABLE_RESULT=success")&&r.contains("REBOOT_REQUIRED=true")),provider+" enable success still requires a reboot");
                check(Files.readString(live.resolve("rollback.txt")).equals("old recovery")&&Files.exists(live.resolve("boot-pending")),provider+" retains old boot recovery evidence");
                check(Files.exists(staged.resolve("skip_mount"))&&Files.exists(staged.resolve("skip_mountify")),provider+" preserves mount-provider markers");
            }
            try(Fixture f=new Fixture()) {
                Path live=f.module("modules",f.generation);f.success(provider);
                check(!Files.exists(live.resolve("disable")),provider+" supports installer writing directly to live directory");
            }
            try(Fixture f=new Fixture()) {
                Path live=f.module("modules",UUID.randomUUID().toString()),staged=f.module("modules_update",f.generation);
                Files.writeString(live.resolve("rollback.txt"),"failed boot");Files.createFile(staged.resolve("boot-pending"));
                fails(f,provider,args->{throw new IOException("installer failed");},"ROOT_INSTALL_FAILED");
                check(f.calls.size()==1&&Files.exists(live.resolve("disable"))&&Files.exists(staged.resolve("disable")),provider+" install failure never attempts enable");
                check(Files.readString(live.resolve("rollback.txt")).equals("failed boot")&&Files.exists(staged.resolve("boot-pending")),provider+" install failure preserves recovery markers");
                check(f.reports.stream().noneMatch(r->r.contains("ENABLE_RESULT=success")),provider+" installation failure cannot report enable success");
            }
        }
        for(RootInstaller.Provider provider:new RootInstaller.Provider[]{RootInstaller.Provider.KERNELSU,RootInstaller.Provider.APATCH}) {
            try(Fixture f=new Fixture()) {
                Path live=f.module("modules",f.generation),staged=f.module("modules_update",f.generation);
                fails(f,provider,args->{if(isEnable(args))throw new IOException("daemon enable failed");},"ROOT_ENABLE_FAILED");
                check(Files.exists(live.resolve("disable"))&&Files.exists(staged.resolve("disable")),provider+" rejected daemon enable does not bypass provider state");
                check(f.reports.stream().anyMatch(r->r.startsWith("INSTALL_RESULT=success"))&&f.reports.stream().anyMatch(r->r.startsWith("ENABLE_RESULT=failed")),provider+" distinguishes installation success from enable failure");
            }
            try(Fixture f=new Fixture()) {
                Path live=f.module("modules",f.generation);
                fails(f,provider,args->{},"ROOT_ENABLE_FAILED");
                check(Files.exists(live.resolve("disable")),provider+" does not trust daemon success without checking enabled state");
            }
        }
        try(Fixture f=new Fixture()) {
            f.module("modules",f.generation);Path staged=f.module("modules_update",UUID.randomUUID().toString());
            fails(f,RootInstaller.Provider.MAGISK,args->{},"ROOT_ENABLE_FAILED");
            check(Files.exists(staged.resolve("disable")),"stale staged update cannot be enabled even when live generation matches");
        }
        try(Fixture f=new Fixture()) {
            fails(f,RootInstaller.Provider.MAGISK,args->{},"ROOT_ENABLE_FAILED");
            check(f.calls.size()==1,"successful installer with no module is rejected");
        }
        for(String marker:new String[]{"remove","boot-pending","rollback.txt"}) {
            try(Fixture f=new Fixture()) {
                Path staged=f.module("modules_update",f.generation);Files.writeString(staged.resolve(marker),"preserve");
                fails(f,RootInstaller.Provider.KERNELSU,args->{},"ROOT_ENABLE_FAILED");
                check(f.calls.size()==1&&Files.exists(staged.resolve("disable"))&&Files.readString(staged.resolve(marker)).equals("preserve"),"unexpected new-module "+marker+" is preserved and blocks enable");
            }
        }
        try(Fixture f=new Fixture()) {
            Path live=f.module("modules",UUID.randomUUID().toString()),staged=f.module("modules_update",f.generation);Files.createFile(live.resolve("remove"));
            fails(f,RootInstaller.Provider.MAGISK,args->{},"ROOT_ENABLE_FAILED");
            check(Files.exists(live.resolve("remove"))&&Files.exists(staged.resolve("disable")),"pending live uninstall is never silently cancelled");
        }
        try(Fixture f=new Fixture()) {
            Path live=f.module("modules",f.generation);Files.writeString(live.resolve("module.prop"),"id=other-module\n");
            fails(f,RootInstaller.Provider.MAGISK,args->{},"ROOT_ENABLE_FAILED");
            check(Files.exists(live.resolve("disable")),"different module identity is not enabled");
        }
        try(Fixture f=new Fixture()) {
            Path live=f.module("modules",f.generation),staged=f.adb.resolve("modules_update").resolve(RootInstaller.MODULE_ID);Files.createDirectories(staged.getParent());Files.createSymbolicLink(staged,live);
            fails(f,RootInstaller.Provider.MAGISK,args->{},"ROOT_ENABLE_FAILED");
            check(Files.exists(live.resolve("disable")),"module directory symlink is not followed when enabling");
        }
        for(String identity:new String[]{"invalid",UUID.randomUUID()+" ".repeat(130)}) {
            try(Fixture f=new Fixture()) {
                f.writeZip(identity);fails(f,RootInstaller.Provider.MAGISK,args->{},"ROOT_INSTALL_INVALID");
                check(f.calls.isEmpty(),"invalid generated package rejected before invoking installer");
            }
        }
        System.out.println("ALL PASS "+count);
    }
}
