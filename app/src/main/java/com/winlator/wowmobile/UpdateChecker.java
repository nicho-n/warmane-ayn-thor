package com.winlator.wowmobile;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks this app's GitHub releases for a newer version, downloads its APK and hands it to
 * Android's installer. The request carries nothing about the user: a fixed URL and a generic
 * User-Agent.
 */
public class UpdateChecker {
    private static final String TAG = "UpdateChecker";
    private static final String RELEASES = "https://api.github.com/repos/nicho-n/warmane-ayn-thor/releases?per_page=30";
    private static final String ASSET = "warmane-ayn-thor.apk";

    public static class Release {
        public final String version;
        public final String apkUrl;
        public final long size;
        /** Notes of every release newer than the installed one, newest first, as plain text. */
        public final String changelog;

        Release(String version, String apkUrl, long size, String changelog) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.size = size;
            this.changelog = changelog;
        }
    }

    public interface Progress {
        /** percent 0-100, or -1 when the size isn't known. */
        void onProgress(int percent);
    }

    /** The latest release if it's newer than [current], else null. Network: call off the main thread. */
    public static Release findNewer(String current) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(RELEASES).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "warmane-ayn-thor-app");
            if (conn.getResponseCode() != 200) return null;
            String body;
            try (InputStream in = conn.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                body = out.toString("UTF-8");
            }
            JSONArray releases = new JSONArray(body);
            java.util.List<JSONObject> newer = new java.util.ArrayList<>();
            for (int i = 0; i < releases.length(); i++) {
                JSONObject r = releases.getJSONObject(i);
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue;
                if (isNewer(version(r), current)) newer.add(r);
            }
            // Newest first.
            java.util.Collections.sort(newer, (a, b) -> isNewer(version(a), version(b)) ? -1 : isNewer(version(b), version(a)) ? 1 : 0);
            StringBuilder notes = new StringBuilder();
            for (JSONObject r : newer) {
                if (notes.length() > 0) notes.append("\n\n");
                notes.append("v").append(version(r)).append("\n").append(plain(r.optString("body", "")));
            }
            for (JSONObject r : newer) {
                JSONArray assets = r.optJSONArray("assets");
                if (assets == null) continue;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (ASSET.equals(a.optString("name"))) {
                        return new Release(version(r), a.optString("browser_download_url"), a.optLong("size", -1), notes.toString().trim());
                    }
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "update check failed: " + e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static String version(JSONObject release) {
        return release.optString("tag_name", "").replaceFirst("^v", "");
    }

    /**
     * Release notes (GitHub markdown) as plain text: no install line or disclaimer, bullets as
     * "•", no bold or heading marks.
     */
    static String plain(String markdown) {
        StringBuilder out = new StringBuilder();
        for (String line : markdown.replace("\r", "").split("\n")) {
            String t = line.trim();
            if (t.startsWith("**Install:**") || t.startsWith("Requires your own")) continue;
            String indent = line.substring(0, line.length() - line.replaceFirst("^\\s+", "").length());
            t = t.replaceFirst("^#+\\s*", "").replace("**", "").replace("`", "");
            if (t.startsWith("- ")) t = (indent.length() >= 2 ? "    ◦ " : "• ") + t.substring(2);
            out.append(t).append("\n");
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").trim();
    }

    /** Compares dotted versions number by number ("1.10.0" is newer than "1.9.2"). */
    static boolean isNewer(String candidate, String current) {
        if (candidate.isEmpty()) return false;
        String[] a = candidate.split("\\."), b = current.replaceAll("[^0-9.].*$", "").split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? parse(a[i]) : 0, y = i < b.length ? parse(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*$", "")); } catch (NumberFormatException e) { return 0; }
    }

    /** Downloads the APK into the app's files (update/). Network: call off the main thread. */
    public static File download(Activity activity, Release release, Progress progress) throws Exception {
        File dir = new File(activity.getFilesDir(), "update");
        dir.mkdirs();
        File apk = new File(dir, ASSET);
        File part = new File(dir, ASSET + ".part");
        HttpURLConnection conn = (HttpURLConnection) new URL(release.apkUrl).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "warmane-ayn-thor-app");
        try {
            if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
            long total = conn.getContentLengthLong();
            if (total <= 0) total = release.size;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n, lastPercent = -2;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    int percent = total > 0 ? (int) (done * 100 / total) : -1;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progress.onProgress(percent);
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
        if (apk.exists()) apk.delete();
        if (!part.renameTo(apk)) throw new Exception("couldn't save the download");
        return apk;
    }

    /**
     * Opens Android's installer for the downloaded APK. Returns false when the app first needs
     * the "install unknown apps" permission: the settings page for it is opened instead.
     */
    public static boolean install(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
            return false;
        }
        Uri uri = FileProvider.getUriForFile(activity, com.winlator.BuildConfig.APPLICATION_ID + ".FileProvider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
        return true;
    }
}
