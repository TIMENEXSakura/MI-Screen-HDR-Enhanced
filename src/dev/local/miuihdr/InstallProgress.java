package dev.local.miuihdr;

/** Root installer progress protocol; completion is only confirmed by its exit status. */
final class InstallProgress {
    final int percent;
    final String phase;
    private InstallProgress(int percent, String phase) { this.percent=percent; this.phase=phase; }
    static InstallProgress parse(String line) {
        if (line==null || !line.startsWith("PROGRESS=")) return null;
        int separator=line.indexOf('|',9);
        if (separator<10 || separator>12) return null;
        String number=line.substring(9,separator), phase=line.substring(separator+1).trim();
        if (!number.matches("[0-9]{1,3}") || phase.isEmpty() || phase.indexOf('\n')>=0 || phase.indexOf('\r')>=0) return null;
        int percent=Integer.parseInt(number);
        return percent<=100?new InstallProgress(percent,phase):null;
    }
    static int advance(int current, int reported) { return Math.max(current,Math.min(99,Math.max(0,reported))); }
    static int finish(int current, boolean success) { return success?100:Math.min(99,Math.max(0,current)); }
    static String userLine(String line) {
        if(line==null)return null;
        if(line.startsWith("USER_LOG="))return line.substring(9);
        return line.startsWith("[阶段 ")?line:null;
    }
}
