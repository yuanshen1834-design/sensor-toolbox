package com.dsh.sensorkit;

import android.app.Application;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 启动器：全局捕获崩溃并落盘，便于在手机本地排查。 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Holder.ctx = this;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                write(t, e);
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
        mark("APP_START");
    }

    static void write(Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("time=" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                    .format(new Date()));
            pw.println("thread=" + (t == null ? "?" : t.getName()));
            e.printStackTrace(pw);
            pw.flush();
            dump(sw.toString());
        } catch (Throwable ignored) {
        }
    }

    static void mark(String line) {
        dump(line + " @ " + new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date()));
    }

    private static void dump(String text) {
        Context ctx = Holder.ctx;
        if (ctx == null) return;
        String[] dirs = new String[]{"/sdcard/Download/DSH-Go", null};
        for (String d : dirs) {
            try {
                File dir = d == null ? ctx.getExternalFilesDir(null) : new File(d);
                if (dir == null) continue;
                if (!dir.exists() && !dir.mkdirs()) continue;
                File f = new File(dir, "crash.txt");
                FileOutputStream fos = new FileOutputStream(f, true);
                fos.write((text + "\n----\n").getBytes("UTF-8"));
                fos.close();
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    static class Holder {
        static Context ctx;
    }
}
