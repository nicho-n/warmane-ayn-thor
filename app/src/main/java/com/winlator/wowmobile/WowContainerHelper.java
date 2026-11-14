package com.winlator.wowmobile;

import android.content.Context;
import android.content.Intent;

import com.winlator.XServerDisplayActivity;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.AppUtils;
import com.winlator.core.Callback;
import com.winlator.core.FileUtils;
import com.winlator.core.StringUtils;
import com.winlator.core.WineUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * Manages the single Winlator container used to run WoW, plus the launch shortcut
 * that ties the container, the game exe and the touch controls profile together.
 */
public class WowContainerHelper {
    public static final String CONTAINER_NAME = "WoW";
    public static final String SHORTCUT_NAME = "World of Warcraft";
    public static final String CONTROLS_PROFILE_NAME = "WoW ConsolePortLK";
    public static final String GAME_DRIVE_LETTER = "F";
    public static final String DEFAULT_SCREEN_SIZE = "960x432";

    private final Context context;
    private final ContainerManager manager;

    public WowContainerHelper(Context context) {
        this.context = context;
        this.manager = new ContainerManager(context);
    }

    public Container getContainer() {
        for (Container container : manager.getContainers()) {
            if (CONTAINER_NAME.equals(container.getName())) return container;
        }
        return null;
    }

    /** Creates the WoW container with tuned defaults if it does not exist yet. */
    public void ensureContainerAsync(GameFolder gameFolder, Callback<Container> callback) {
        String screenSize = getScreenSize(gameFolder);
        Container existing = getContainer();
        if (existing != null) {
            ensureGameDrive(existing, gameFolder);
            boolean changed = false;
            if (!screenSize.equals(existing.getScreenSize())) {
                existing.setScreenSize(screenSize);
                changed = true;
            }
            if (applyCrop(existing, screenSize)) changed = true;
            String envVars = withImitoBox64(existing.getEnvVars(), gameFolder);
            if (!envVars.equals(existing.getEnvVars())) {
                existing.setEnvVars(envVars);
                changed = true;
            }
            if (changed) existing.saveData();
            callback.call(existing);
            return;
        }

        try {
            JSONObject data = new JSONObject();
            data.put("name", CONTAINER_NAME);
            data.put("screenSize", screenSize);
            data.put("envVars", withImitoBox64(Container.DEFAULT_ENV_VARS, gameFolder));
            data.put("graphicsDriver", GraphicsDrivers.getDefaultDriver(context));
            data.put("dxwrapper", Container.DEFAULT_DXWRAPPER);
            data.put("audioDriver", Container.DEFAULT_AUDIO_DRIVER);
            data.put("wincomponents", Container.DEFAULT_WINCOMPONENTS);
            data.put("drives", Container.DEFAULT_DRIVES + GAME_DRIVE_LETTER+":"+gameFolder.root.getPath());
            data.put("startupSelection", Container.STARTUP_SELECTION_AGGRESSIVE);
            manager.createContainerAsync(data, (container) -> {
                if (container != null && applyCrop(container, screenSize)) container.saveData();
                callback.call(container);
            });
        }
        catch (JSONException e) {
            callback.call(null);
        }
    }

    /**
     * WoW Imito folders keep their own Config.wtf (windowed, e.g. 1920x1080), which
     * the provisioner no longer rewrites to 960x432. Give the Windows desktop the
     * same size so the game window fills it instead of hanging off a small desktop.
     */
    private String getScreenSize(GameFolder gameFolder) {
        Provisioner provisioner = new Provisioner(context, gameFolder);
        if (!provisioner.isWowImitoFolder()) return DEFAULT_SCREEN_SIZE;
        String resolution = provisioner.getConfigValue("gxResolution");
        if (resolution != null && resolution.matches("\\d{3,4}x\\d{3,4}")) {
            // CROP_BOTTOM extra rows below the visible picture, for the WoWMobileLink data row.
            String[] wh = resolution.split("x");
            return wh[0] + "x" + (Integer.parseInt(wh[1]) + CROP_BOTTOM);
        }
        return DEFAULT_SCREEN_SIZE;
    }

    /** Hidden rows at the bottom of the desktop (see ViewTransformation.cropBottom). */
    public static final int CROP_BOTTOM = 5;

    /** Stores how many bottom rows the renderer hides; returns true if it changed. */
    private static boolean applyCrop(Container container, String screenSize) {
        String crop = String.valueOf(screenSize.equals(DEFAULT_SCREEN_SIZE) ? 0 : CROP_BOTTOM);
        if (crop.equals(container.getExtra("cropBottom"))) return false;
        container.putExtra("cropBottom", crop);
        return true;
    }

    /**
     * Box64 settings WoW Imito ships (assets/box64/env_vars.json defaults in
     * pub.wowimito 1.4.1), applied to WoW Imito folders. Container env vars are
     * applied after the Box64 preset, so these take precedence.
     */
    private static final String[][] IMITO_BOX64 = {
        {"BOX64_DYNAREC_SAFEFLAGS", "2"}, {"BOX64_DYNAREC_FASTNAN", "1"}, {"BOX64_DYNAREC_FASTROUND", "1"},
        {"BOX64_DYNAREC_X87DOUBLE", "0"}, {"BOX64_DYNAREC_BIGBLOCK", "1"}, {"BOX64_DYNAREC_STRONGMEM", "0"},
        {"BOX64_DYNAREC_FORWARD", "128"}, {"BOX64_DYNAREC_CALLRET", "1"}, {"BOX64_DYNAREC_WAIT", "1"},
        {"BOX64_DYNAREC_NATIVEFLAGS", "0"}, {"BOX64_DYNAREC_WEAKBARRIER", "0"},
    };

    private String withImitoBox64(String current, GameFolder gameFolder) {
        if (!new Provisioner(context, gameFolder).isWowImitoFolder()) return current;
        com.winlator.core.EnvVars envVars = new com.winlator.core.EnvVars(current);
        for (String[] item : IMITO_BOX64) envVars.put(item[0], item[1]);
        return envVars.toString();
    }

    /** Keeps the F: drive pointing at the currently selected game folder. */
    private void ensureGameDrive(Container container, GameFolder gameFolder) {
        String drives = container.getDrives();
        String gameDrive = GAME_DRIVE_LETTER+":"+gameFolder.root.getPath();

        StringBuilder sb = new StringBuilder();
        boolean found = false;
        for (com.winlator.container.Drive drive : Container.drivesIterator(drives)) {
            if (drive.letter.equals(GAME_DRIVE_LETTER)) {
                sb.append(gameDrive);
                found = true;
            }
            else sb.append(drive.letter).append(":").append(drive.path);
        }
        if (!found) sb.append(gameDrive);

        String newDrives = sb.toString();
        if (!newDrives.equals(drives)) {
            container.setDrives(newDrives);
            container.saveData();
        }
    }

    /** Writes the launch shortcut binding exe + controls profile, then returns it. */
    public File ensureShortcut(Container container, GameFolder gameFolder) {
        File desktopDir = new File(container.getUserDir(), "Desktop");
        if (!desktopDir.isDirectory()) desktopDir.mkdirs();

        File shortcutFile = new File(desktopDir, SHORTCUT_NAME+".desktop");
        String dosPath = WineUtils.unixToDOSPath(gameFolder.getWowExe().getPath(), container);

        // Shortcut.unescapeDOSPath applies its unescape pass twice, so the stored
        // Exec path needs two rounds of escaping to survive parsing.
        String content = "[Desktop Entry]\n" +
            "Name="+SHORTCUT_NAME+"\n" +
            "Exec=wine "+StringUtils.escapeDOSPath(StringUtils.escapeDOSPath(dosPath))+"\n" +
            "StartupWMClass=wow.exe\n" +
            "\n[Extra Data]\n";

        // The bundled touch profile sends letter keys for the original
        // ConsolePortLK. WoW Imito's ConsolePort uses F1-F12 from the
        // controller instead, so those buttons would misfire: use plain
        // touchpad taps there.
        int profileId = new Provisioner(context, gameFolder).isWowImitoFolder() ? 0 : getControlsProfileId();
        if (profileId > 0) content += "controlsProfile="+profileId+"\n";

        FileUtils.writeString(shortcutFile, content);
        return shortcutFile;
    }

    private int getControlsProfileId() {
        InputControlsManager inputControlsManager = new InputControlsManager(context);
        for (ControlsProfile profile : inputControlsManager.getProfiles()) {
            if (CONTROLS_PROFILE_NAME.equals(profile.getName())) return profile.id;
        }
        return 0;
    }

    public Intent createLaunchIntent(Container container, File shortcutFile) {
        Intent intent = new Intent(context, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("shortcut_path", shortcutFile.getPath());
        return intent;
    }
}
