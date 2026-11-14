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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Installs the addons this app needs into the game's Interface/AddOns:
 *  - ThorControls and WoWMobileLink (this app's own; also updated on Play when the app
 *    carries a newer version),
 *  - NotPlater, if it isn't there (touch targeting reads its nameplates),
 *  - the bundled stock ConsolePortLK, replacing whatever ConsolePort is there, except WoW
 *    Imito's modified ConsolePort, which is kept: it runs much smoother in combat (stock
 *    ConsolePortLK's round buttons drop frames on every cast). WoW Imito's own addons are
 *    moved to Interface/AddOns-disabled.
 * Before installing, the whole AddOns folder is zipped into the game folder. Only the
 * folders of the addons being installed (or moved) are touched.
 */
public class AddonInstaller {
    private static final String ASSET_DIR = "wowmobile/addons";
    private static final String CONSOLEPORT_ZIP = "wowmobile/ConsolePortLK.zip";
    private static final String THOR_CONTROLS = "ThorControls";
    private static final String LINK = "WoWMobileLink";
    private static final String NOTPLATER = "NotPlater-3.3.5";
    /** This app's own addons: always installed, updated when newer. */
    private static final String[] OWN = {THOR_CONTROLS, LINK};
    /** WoW Imito's addons, which only work with its modified ConsolePort. */
    private static final String[] WOW_IMITO = {"WoWImito", "ImitoTouchUI", "ThorFixes"};
    public static final String DISABLED_DIR = "AddOns-disabled";

    private final Context context;
    private final GameFolder gameFolder;

    public AddonInstaller(Context context, GameFolder gameFolder) {
        this.context = context;
        this.gameFolder = gameFolder;
    }

    private File addOnsDir() {
        return gameFolder.getAddOnsDir();
    }

    public boolean needsNotPlater() {
        return !new File(addOnsDir(), NOTPLATER + "/" + NOTPLATER + ".toc").isFile();
    }

    /** WoW Imito's addons present, which [install] moves out (they need WoW Imito's ConsolePort). */
    public List<String> wowImitoAddonsToDisable() {
        List<String> names = new ArrayList<>();
        for (String name : WOW_IMITO) if (new File(addOnsDir(), name).isDirectory()) names.add(name);
        return names;
    }

    /** WoW Imito's modified ConsolePort is installed (see the class comment); it's kept. */
    public boolean hasWowImitoConsolePort() {
        return new File(addOnsDir(), "ConsolePort/IMITO-CHANGES.txt").isFile()
            || new File(addOnsDir(), "ConsolePort/ImitoIntegration.lua").isFile();
    }

    /** Names of the addons [install] will put in, for the confirmation popup. */
    public List<String> plan() {
        List<String> names = new ArrayList<>();
        if (!hasWowImitoConsolePort()) names.add("ConsolePortLK");
        for (String name : OWN) names.add(name);
        if (needsNotPlater()) names.add("NotPlater");
        return names;
    }

    /** Zips Interface/AddOns into the game folder; returns the zip, or null if there was nothing to back up. */
    public File backup() throws IOException {
        File addOnsDir = addOnsDir();
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

    /** Installs everything in [plan]. Call [backup] first. */
    public void install() throws IOException {
        File addOnsDir = addOnsDir();
        if (!addOnsDir.isDirectory() && !addOnsDir.mkdirs()) throw new IOException("can't create " + addOnsDir);

        boolean keepConsolePort = hasWowImitoConsolePort();
        disableWowImitoAddons();
        if (!keepConsolePort) installConsolePort();
        for (String name : OWN) installAddon(name);
        if (needsNotPlater()) installAddon(NOTPLATER);
    }

    /**
     * Updates this app's own addons when the app carries a newer version (compared by
     * "## Version" in the .toc). Addons that aren't installed are left alone.
     */
    public void updateInstalled() {
        for (String name : OWN) {
            File toc = new File(new File(addOnsDir(), name), name + ".toc");
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

    /** Moves WoW Imito's addons to Interface/AddOns-disabled (WoW doesn't load them from there). */
    private void disableWowImitoAddons() throws IOException {
        File disabledDir = new File(addOnsDir().getParentFile(), DISABLED_DIR);
        for (String name : wowImitoAddonsToDisable()) {
            if (!disabledDir.isDirectory() && !disabledDir.mkdirs()) throw new IOException("can't create " + disabledDir);
            File target = new File(disabledDir, name);
            if (target.exists()) FileUtils.delete(target);
            if (!new File(addOnsDir(), name).renameTo(target)) throw new IOException("can't move " + name);
        }
    }

    /** Replaces every ConsolePort* folder the bundled ConsolePortLK contains, then extracts it. */
    private void installConsolePort() throws IOException {
        File addOnsDir = addOnsDir();
        Set<String> folders = new LinkedHashSet<>();
        try (ZipInputStream zis = new ZipInputStream(context.getAssets().open(CONSOLEPORT_ZIP))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String top = entry.getName().split("/")[0];
                if (!top.isEmpty()) folders.add(top);
            }
        }
        for (String folder : folders) {
            File dir = new File(addOnsDir, folder);
            if (dir.exists()) FileUtils.delete(dir);
        }
        try (ZipInputStream zis = new ZipInputStream(context.getAssets().open(CONSOLEPORT_ZIP))) {
            byte[] buffer = new byte[64 * 1024];
            ZipEntry entry;
            String base = addOnsDir.getCanonicalPath();
            while ((entry = zis.getNextEntry()) != null) {
                File target = new File(addOnsDir, entry.getName());
                if (!target.getCanonicalPath().startsWith(base)) continue;
                if (entry.isDirectory()) {
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(target))) {
                    int count;
                    while ((count = zis.read(buffer)) != -1) os.write(buffer, 0, count);
                }
            }
        }
        // Unlike the provisioner's fresh install, bindings stay per character here: that's
        // how WoW Imito's ConsolePort saved them, so existing characters keep their bindings.
    }

    private void installAddon(String name) throws IOException {
        File addOnsDir = addOnsDir();
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
