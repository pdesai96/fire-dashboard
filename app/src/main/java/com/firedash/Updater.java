package com.firedash;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Checks GitHub for a newer build. Android won't let an app replace itself silently,
 * so installing still needs one tap on the system's Install button.
 */
final class Updater {
    private Updater() {}

    static final String BASE = "https://github.com/" + LiveConfig.REPO + "/releases/latest/download/";

    static int installedVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** The latest published version code, or 0 if unknown. Network: call off the main thread. */
    static int latestVersion() throws IOException {
        Map<String, Object> m = United.map(Json.parse(Net.get(BASE + "version.json")));
        Object v = m.get("versionCode");
        return v instanceof Double ? ((Double) v).intValue() : 0;
    }

    /** Downloads the new APK. Network: call off the main thread. */
    static File download(Context c) throws IOException {
        File dir = c.getExternalCacheDir() != null ? c.getExternalCacheDir() : c.getCacheDir();
        File apk = new File(dir, "fire-dashboard-update.apk");
        Net.download(BASE + "fire-dashboard.apk", apk);
        apk.setReadable(true, false);
        return apk;
    }

    /** Opens the system installer. */
    static void install(Context c, File apk) {
        if (Build.VERSION.SDK_INT >= 24) {
            // Newer Android needs a content:// link; send people to the download page instead.
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(BASE + "fire-dashboard.apk"));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(web);
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }
}
