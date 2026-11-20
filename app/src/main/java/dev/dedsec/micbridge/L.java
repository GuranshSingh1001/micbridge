package dev.dedsec.micbridge;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tiny logger: logcat + a file we can pull over adb. */
final class L {
    static final String TAG = "MicBridge";
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private L() {}

    static void i(String msg) {
        Log.i(TAG, msg);
    }

    static void e(String msg) {
        Log.e(TAG, msg);
    }

    static void f(Context ctx, String msg) {
        i(msg);
        if (ctx == null) return;
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "micbridge.log");
            if (out.length() > 512 * 1024) {
                File old = new File(dir, "micbridge.old.log");
                if (old.exists()) old.delete();
                out.renameTo(old);
            }
            FileWriter w = new FileWriter(out, true);
            w.write(FMT.format(new Date()) + "  " + msg + "\n");
            w.close();
        } catch (Throwable t) {
            Log.e(TAG, "log file failed", t);
        }
    }
}
