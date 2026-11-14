package com.winlator.wowmobile;

import android.content.Context;
import android.content.res.AssetManager;

import com.winlator.core.FileUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Installs this app's own addons (bundled from the repo's addons/ folder into
 * assets/wowmobile/addons) into the game's Interface/AddOns. Only the bundled addons'
 * own folders are ever replaced; before installing, the whole AddOns folder is zipped
 * into the game folder as a backup.
 */
public class AddonInstaller {
    private static final String ASSET_DIR = "wowmobile/addons";
    /** Needed for the bottom screen's Target page. */
    private static final String LINK = "WoWMobileLink";
    /** Only useful (and only loads) alongside WoW Imito's addons. */
    private static final String THOR_FIXES = "ThorFixes";

    private final Context context;
    private final GameFolder gameFolder;

    public AddonInstaller(Context context, GameFolder gameFolder) {
        this.context = context;
        this.gameFolder = gameFolder;
    }

    /** The bundled addons that apply to this game folder. */
    public List<String> plan() {
        List<String> names = new ArrayList<>();
        names.add(LINK);
        if (new Provisioner(context, gameFolder).isWowImitoFolder()) names.add(THOR_FIXES);
        return names;
    }

    /** Zips Interface/AddOns into the game folder; returns the zip, or null if there was nothing to back up. */
    public File backup() throws IOException {
        File addOnsDir = gameFolder.getAddOnsDir();
        if (!addOnsDir.isDirectory()) return null;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File zip = new File(gameFolder.root, "AddOns-backup-" + stamp + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip)))) {
            zipDir(zos, addOnsDir, "AddOns/");
        }
        catch (IOException e) {
            zip.delete();
            throw e;
        }
        return zip;
    }

    private static void zipDir(ZipOutputStream zos, File dir, String prefix) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        byte[] buffer = new byte[64 * 1024];
        for (File file : files) {
            if (file.isDirectory()) {
                zos.putNextEntry(new ZipEntry(prefix + file.getName() + "/"));
                zos.closeEntry();
                zipDir(zos, file, prefix + file.getName() + "/");
            }
            else {
                zos.putNextEntry(new ZipEntry(prefix + file.getName()));
                try (InputStream is = new FileInputStream(file)) {
                    int count;
                    while ((count = is.read(buffer)) != -1) zos.write(buffer, 0, count);
                }
                zos.closeEntry();
            }
        }
    }

    /** Installs every addon in [plan], replacing only those addons' own folders. */
    public void install() throws IOException {
        for (String name : plan()) installAddon(name);
    }

    /**
     * Updates already-installed bundled addons when the app carries a newer version
     * (compared by "## Version" in the .toc). Addons that aren't installed are left alone.
     */
    public void updateInstalled() {
        for (String name : plan()) {
            File toc = new File(new File(gameFolder.getAddOnsDir(), name), name + ".toc");
            if (!toc.isFile()) continue;
            String installed = tocVersion(FileUtils.readString(toc));
            String bundled = tocVersion(readAsset(ASSET_DIR + "/" + name + "/" + name + ".toc"));
            if (compareVersions(bundled, installed) > 0) {
                try {
                    installAddon(name);
                }
                catch (IOException e) {}
            }
        }
    }

    private void installAddon(String name) throws IOException {
        File addOnsDir = gameFolder.getAddOnsDir();
        if (!addOnsDir.isDirectory() && !addOnsDir.mkdirs()) throw new IOException("can't create " + addOnsDir);
        File target = new File(addOnsDir, name);
        if (target.exists()) FileUtils.delete(target);
        copyAssetDir(context.getAssets(), ASSET_DIR + "/" + name, target);
    }

    private static void copyAssetDir(AssetManager assets, String path, File target) throws IOException {
        String[] children = assets.list(path);
        if (children == null || children.length == 0) {
            File parent = target.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream is = assets.open(path); OutputStream os = new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = is.read(buffer)) != -1) os.write(buffer, 0, count);
            }
            return;
        }
        target.mkdirs();
        for (String child : children) copyAssetDir(assets, path + "/" + child, new File(target, child));
    }

    private String readAsset(String path) {
        try (InputStream is = context.getAssets().open(path)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = is.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            return "";
        }
    }

    private static String tocVersion(String toc) {
        for (String line : toc.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## Version:")) return trimmed.substring("## Version:".length()).trim();
        }
        return "0";
    }

    /** Compares dotted versions numerically ("1.13" > "1.9"). */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = i < pa.length ? parse(pa[i]) : 0;
            int y = i < pb.length ? parse(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        }
        catch (NumberFormatException e) {
            return 0;
        }
    }
}
