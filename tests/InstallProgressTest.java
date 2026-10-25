package dev.local.miuihdr;

public final class InstallProgressTest {
    private static int passed;
    private static void check(boolean result,String message) {
        if(!result)throw new AssertionError(message);
        passed++;System.out.println("PASS "+message);
    }
    public static void main(String[] args) {
        InstallProgress event=InstallProgress.parse("PROGRESS=42|已检查候选库 7/20");
        check(event!=null&&event.percent==42&&event.phase.equals("已检查候选库 7/20"),"structured progress retains percentage and stage");
        for(String line:new String[]{"PATCH_OK offset=0x42", "PROGRESS=-1|invalid", "PROGRESS=101|invalid", "PROGRESS=abc|invalid", "PROGRESS=1| ", "PROGRESS=20|stage\nFORGED", "PROGRESS=20|stage\rFORGED", "PROGRESS=999999999999|invalid"})
            check(InstallProgress.parse(line)==null,"reject nonprogress or malformed event: "+line.replace('\n',' ').replace('\r',' '));
        check(InstallProgress.advance(60,25)==60,"later stage or nested subprocess cannot regress progress");
        check(InstallProgress.advance(85,100)==99,"reported completion waits for a successful process exit");
        check(InstallProgress.finish(85,false)==85,"failure retains the last completed percentage");
        check(InstallProgress.finish(100,false)==99,"failed exit cannot display success as 100 percent");
        check(InstallProgress.finish(99,true)==100,"successful exit completes progress");
        check(InstallProgress.userLine("CANDIDATE index=1 path=/system/lib64/libother.so")==null
            &&InstallProgress.userLine("ELF_SEGMENT file_offset=0x100")==null
            &&InstallProgress.userLine("PROGRESS=40|扫描阶段")==null,"candidate scans and raw technical details never flood the visible log");
        check(InstallProgress.userLine("USER_LOG=找到目标库：/system/lib64/libsurfaceflinger.so").equals("找到目标库：/system/lib64/libsurfaceflinger.so"),"selected library summary is visible without protocol prefix");
        check(InstallProgress.userLine("[阶段 2/7 · 扫描阶段] 正在查找").contains("正在查找"),"Chinese stage explanation remains visible");
        check(InstallProgress.userLine("USER_LOG=安装未完成：没有识别到支持的运行库。").contains("安装未完成"),"concise failure explanation remains visible");
        System.out.println("INSTALL_PROGRESS_CHECKS="+passed);
    }
}
