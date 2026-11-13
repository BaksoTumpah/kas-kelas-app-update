package com.kaskelas.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Pembaruan aplikasi otomatis dari GitHub Release (mirip Magisk):
 *  - info()     : versi terpasang, jenis build (debug/rilis), izin pasang
 *  - download() : unduh APK dari release repo resmi (hanya https GitHub), cek SHA-256, laporkan progres
 *  - install()  : buka pemasang paket Android untuk APK yang sudah diunduh
 * Android tetap menampilkan layar konfirmasi pemasangan; pemasangan senyap tidak dimungkinkan tanpa root.
 */
@CapacitorPlugin(name = "KasUpdate")
public class KasUpdatePlugin extends Plugin {

    private static final String ALLOWED_PREFIX = "https://github.com/BaksoTumpah/kas-kelas-app-update/releases/download/";
    private static final long MAX_BYTES = 200L * 1024 * 1024;

    private File dir() {
        File d = new File(getContext().getCacheDir(), "update");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    @Override
    public void load() {
        // bersihkan sisa unduhan lama setiap aplikasi dibuka
        try {
            File[] fs = dir().listFiles();
            if (fs != null) for (File f : fs) f.delete();
        } catch (Exception ignored) { }
    }

    @PluginMethod
    public void info(PluginCall call) {
        Context c = getContext();
        JSObject o = new JSObject();
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            o.put("versionName", pi.versionName);
            o.put("versionCode", Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : (long) pi.versionCode);
        } catch (Exception ignored) { }
        o.put("debuggable", (c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        o.put("canInstall", Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls());
        call.resolve(o);
    }

    @PluginMethod
    public void openInstallSettings(PluginCall call) {
        try {
            Context c = getContext();
            Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()));
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(s);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? "Gagal membuka pengaturan." : e.getMessage());
        }
    }

    @PluginMethod
    public void download(final PluginCall call) {
        final String url = call.getString("url", "");
        final String sha = call.getString("sha256", "");
        if (url == null || !url.startsWith(ALLOWED_PREFIX) || !url.toLowerCase().contains(".apk")) {
            call.reject("Alamat unduhan tidak diizinkan.");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection con = null;
                File part = null;
                try {
                    File d = dir();
                    File[] old = d.listFiles();
                    if (old != null) for (File f : old) f.delete();
                    part = new File(d, "kas-kelas-update.apk.part");
                    String cur = url;
                    for (int hop = 0; hop < 6; hop++) {
                        con = (HttpURLConnection) new URL(cur).openConnection();
                        con.setInstanceFollowRedirects(false);
                        con.setConnectTimeout(20000);
                        con.setReadTimeout(30000);
                        con.setRequestProperty("User-Agent", "KasKelas-Updater");
                        int code = con.getResponseCode();
                        if (code >= 300 && code < 400) {
                            String loc = con.getHeaderField("Location");
                            con.disconnect();
                            if (loc == null || !loc.startsWith("https://")) throw new Exception("Pengalihan tidak aman.");
                            cur = loc;
                            continue;
                        }
                        if (code != 200) throw new Exception("Server membalas HTTP " + code);
                        break;
                    }
                    long total = con.getContentLengthLong();
                    if (total > MAX_BYTES) throw new Exception("Ukuran berkas tidak wajar.");
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    InputStream in = con.getInputStream();
                    FileOutputStream out = new FileOutputStream(part);
                    byte[] buf = new byte[32768];
                    long done = 0, lastNote = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        done += n;
                        if (done > MAX_BYTES) throw new Exception("Ukuran berkas tidak wajar.");
                        long now = System.currentTimeMillis();
                        if (now - lastNote > 200) {
                            lastNote = now;
                            JSObject p = new JSObject();
                            p.put("done", done);
                            p.put("total", total);
                            notifyListeners("progress", p);
                        }
                    }
                    out.close();
                    in.close();
                    JSObject p = new JSObject();
                    p.put("done", done);
                    p.put("total", total > 0 ? total : done);
                    notifyListeners("progress", p);
                    if (sha != null && sha.length() == 64) {
                        StringBuilder sb = new StringBuilder();
                        for (byte b : md.digest()) sb.append(String.format("%02x", b));
                        if (!sb.toString().equalsIgnoreCase(sha)) throw new Exception("Berkas rusak (SHA-256 tidak cocok).");
                    }
                    File fin = new File(dir(), "kas-kelas-update.apk");
                    if (fin.exists()) fin.delete();
                    if (!part.renameTo(fin)) throw new Exception("Gagal menyimpan berkas.");
                    JSObject r = new JSObject();
                    r.put("path", fin.getAbsolutePath());
                    r.put("size", done);
                    call.resolve(r);
                } catch (Exception e) {
                    try { if (part != null) part.delete(); } catch (Exception ignored) { }
                    call.reject(e.getMessage() == null ? "Unduhan gagal." : e.getMessage());
                } finally {
                    if (con != null) con.disconnect();
                }
            }
        }).start();
    }

    @PluginMethod
    public void install(PluginCall call) {
        try {
            String path = call.getString("path", "");
            File f = new File(path == null ? "" : path);
            if (!f.getCanonicalPath().startsWith(dir().getCanonicalPath() + File.separator) || !f.isFile())
                throw new Exception("Berkas pembaruan tidak ditemukan.");
            Context c = getContext();
            if (Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
                Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()));
                s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(s);
                JSObject o = new JSObject();
                o.put("needPermission", true);
                call.resolve(o);
                return;
            }
            Uri u = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            JSObject o = new JSObject();
            o.put("started", true);
            call.resolve(o);
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? "Gagal membuka pemasang." : e.getMessage());
        }
    }
}
