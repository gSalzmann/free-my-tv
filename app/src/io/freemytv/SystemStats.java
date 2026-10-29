package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/**
 * The drawer's TV card: CPU, memory, storage, temperature and how long the TV has been on. Read on a thread of its own,
 * at most every 15 s and only while the drawer is open (the home screen asks). Memory and storage come from Android;
 * the CPU and the temperature from /proc/stat and /sys/class/thermal, which Android 8+ keeps from apps, so through
 * the TV's own ADB where the file can't be read: only in Free My TV mode, where its key is already allowed (never a prompt).
 * Also restarts the TV, the drawer's Restart button.
 */
final class SystemStats {
    /** One reading. NaN or 0: not known on this TV. */
    static final class Reading {
        float cpu = Float.NaN, temp = Float.NaN;   // 0 to 1; °C
        long memUsed, memTotal, diskUsed, diskTotal, uptime;
        long at;
    }

    static final long EVERY_MS = 15_000;
    /** Two looks at /proc/stat this far apart: the CPU's use in between. */
    private static final int CPU_WINDOW_MS = 1000;
    private static final String THERMAL = "/sys/class/thermal";

    private static SystemStats instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Reading now = new Reading();
    private boolean busy;
    private Runnable listener;

    private SystemStats(Context c) {
        app = c.getApplicationContext();
    }

    static synchronized SystemStats get(Context c) {
        if (instance == null) instance = new SystemStats(c);
        return instance;
    }

    Reading now() {
        return now;
    }

    /** Called on the main thread after each new reading. */
    void setListener(Runnable r) {
        listener = r;
    }

    /** Reads again unless the last reading is recent or one is on its way. Main thread. */
    void refreshIfStale() {
        if (busy || SystemClock.elapsedRealtime() - now.at < EVERY_MS - 1000) return;
        busy = true;
        new Thread(() -> {
            Reading r = new Reading();
            try {
                read(r);
            } catch (Exception e) {   // nothing may escape: :home's watchdog would count it as a crash
                AppLog.w("TV card: " + e);
            }
            r.at = SystemClock.elapsedRealtime();
            main.post(() -> {
                now = r;
                busy = false;
                if (listener != null) listener.run();
            });
        }, "tv-card").start();
    }

    private void read(Reading r) {
        r.uptime = SystemClock.elapsedRealtime();
        ActivityManager am = app.getSystemService(ActivityManager.class);
        if (am != null) {
            ActivityManager.MemoryInfo m = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(m);
            r.memTotal = m.totalMem;
            r.memUsed = m.totalMem - m.availMem;
        }
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            r.diskTotal = fs.getTotalBytes();
            r.diskUsed = r.diskTotal - fs.getAvailableBytes();
        } catch (IllegalArgumentException ignored) {
        }
        // The CPU and the temperature: straight from the files where the TV lets apps read them, else through ADB.
        String first = readLine("/proc/stat");
        if (first != null) {
            SystemClock.sleep(CPU_WINDOW_MS);
            r.cpu = cpu(first, readLine("/proc/stat"));
            r.temp = temperature(localThermal());
            if (!Float.isNaN(r.temp)) return;
        }
        if (!Capabilities.adb(app)) return;
        try (Adb adb = Adb.connect(app, 1000, null)) {
            String out = adb.shell("head -1 /proc/stat; sleep " + CPU_WINDOW_MS / 1000 + "; head -1 /proc/stat; "
                    + "for z in " + THERMAL + "/thermal_zone*; do echo \"zone $(cat $z/type) $(cat $z/temp)\"; done 2>/dev/null");
            String a = null, b = null;
            StringBuilder zones = new StringBuilder();
            for (String line : out.split("\n")) {
                if (line.startsWith("cpu ")) {
                    if (a == null) a = line; else b = line;
                } else if (line.startsWith("zone ")) {
                    zones.append(line.substring(5)).append('\n');
                }
            }
            if (Float.isNaN(r.cpu)) r.cpu = cpu(a, b);
            r.temp = temperature(zones.toString());
        } catch (IOException e) {
            AppLog.w("TV card: " + e.getMessage());
        }
    }

    /** Busy share between two "cpu …" lines of /proc/stat (idle and iowait are the time it rested). */
    private static float cpu(String a, String b) {
        long[] x = ticks(a), y = ticks(b);
        if (x == null || y == null) return Float.NaN;
        long total = y[0] - x[0], idle = y[1] - x[1];
        return total <= 0 ? Float.NaN : Ui.clamp(1 - idle / (float) total);
    }

    /** {all ticks, idle ticks} of a "cpu  user nice system idle iowait irq softirq steal" line. */
    private static long[] ticks(String line) {
        if (line == null || !line.startsWith("cpu ")) return null;
        String[] f = line.trim().split("\\s+");
        if (f.length < 5) return null;
        long total = 0, idle = 0;
        try {
            for (int i = 1; i < f.length && i <= 8; i++) total += Long.parseLong(f[i]);
            idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
        } catch (NumberFormatException e) {
            return null;
        }
        return new long[] {total, idle};
    }

    /** "type temp" lines from the files, where the TV lets apps read them. */
    private static String localThermal() {
        File[] zones = new File(THERMAL).listFiles((d, name) -> name.startsWith("thermal_zone"));
        if (zones == null) return "";
        StringBuilder s = new StringBuilder();
        for (File z : zones) {
            String type = readLine(z.getPath() + "/type"), temp = readLine(z.getPath() + "/temp");
            if (type != null && temp != null) s.append(type).append(' ').append(temp).append('\n');
        }
        return s.toString();
    }

    /**
     * The chip's temperature from "type temp" lines: a zone named for the CPU or the SoC (MediaTek's "mtktscpu",
     * Amlogic's "soc_thermal"), else the first one. Millidegrees on most kernels, degrees on some.
     */
    private static float temperature(String zones) {
        float first = Float.NaN, chip = Float.NaN;
        for (String line : zones.split("\n")) {
            int sp = line.lastIndexOf(' ');
            if (sp <= 0) continue;
            float t;
            try {
                t = Float.parseFloat(line.substring(sp + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (t > 1000) t /= 1000;
            if (t <= 0 || t > 150) continue;   // a zone with no sensor
            String type = line.substring(0, sp).toLowerCase(java.util.Locale.ROOT);
            if (Float.isNaN(first)) first = t;
            if (Float.isNaN(chip) && (type.contains("cpu") || type.contains("soc"))) chip = t;
        }
        return Float.isNaN(chip) ? first : chip;
    }

    private static String readLine(String path) {
        try (BufferedReader in = new BufferedReader(new FileReader(path))) {
            return in.readLine();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /** Restarts the TV through its own ADB (Free My TV mode). Calls back on the main thread with false if it couldn't. */
    static void restart(Context c, java.util.function.Consumer<Boolean> done) {
        Context app = c.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        if (!Capabilities.adb(app)) {
            done.accept(false);
            return;
        }
        new Thread(() -> {
            boolean ok = false;
            try (Adb adb = Adb.connect(app, 1000, null)) {
                AppLog.w("TV card: restarting the TV");
                ok = true;
                try {
                    adb.shell("svc power reboot || reboot");
                } catch (IOException e) {   // the TV going down closes the connection: the command got there
                    AppLog.w("TV card: restart: " + e.getMessage());
                }
            } catch (Exception e) {
                AppLog.w("TV card: restart: " + e.getMessage());
            }
            boolean result = ok;
            main.post(() -> done.accept(result));
        }, "tv-restart").start();
    }
}
