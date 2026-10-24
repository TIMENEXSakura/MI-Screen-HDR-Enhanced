package dev.local.miuihdr;
import java.io.IOException;
import java.util.Arrays;

public final class RootInstallerTest {
    static int count;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);count++;System.out.println("PASS "+message);}
    public static void main(String[] args)throws Exception {
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
        System.out.println("ALL PASS "+count);
    }
}
