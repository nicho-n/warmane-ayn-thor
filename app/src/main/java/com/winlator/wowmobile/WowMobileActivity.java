package com.winlator.wowmobile;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.container.Container;
import com.winlator.core.AppUtils;
import com.winlator.core.PreloaderDialog;
import com.winlator.xenvironment.RootFSInstaller;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.Executors;

/**
 * WoW Mobile home screen: select the game folder, provision it for
 * ConsolePortLK + touch controls, and play.
 */
public class WowMobileActivity extends AppCompatActivity {
    public static final String PREF_GAME_FOLDER = "wow_game_folder";
    private static final String DONATE_URL = "https://buymeacoffee.com/nichon";
    private SharedPreferences preferences;
    private TextView tvStatus;
    private TextView tvFolder;
    private TextView tvRealmlist;
    private Button btPlay;
    private Button btUpdate;
    private UpdateChecker.Release update;
    private java.io.File updateApk;
    private boolean downloading;
    /** Sent to the "install unknown apps" setting: install as soon as the user comes back with it allowed. */
    private boolean awaitingInstallPermission;
    private final PreloaderDialog preloaderDialog = new PreloaderDialog(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.wow_main_activity);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        tvStatus = findViewById(R.id.TVWowStatus);
        tvFolder = findViewById(R.id.TVWowFolder);
        tvRealmlist = findViewById(R.id.TVWowRealmlist);
        btPlay = findViewById(R.id.BTPlay);
        btUpdate = findViewById(R.id.BTUpdate);

        applyPadStyle();
        btPlay.setOnClickListener((v) -> onPlay());
        findViewById(R.id.BTSelectFolder).setOnClickListener((v) -> showFolderPicker());
        findViewById(R.id.BTWowSettings).setOnClickListener((v) -> startActivity(new Intent(this, WowSettingsActivity.class)));
        findViewById(R.id.BTContainerSettings).setOnClickListener((v) -> startActivity(new Intent(this, ContainerSettingsActivity.class)));
        findViewById(R.id.BTAdvanced).setOnClickListener((v) -> startActivity(new Intent(this, MainActivity.class)));
        findViewById(R.id.BTDonate).setOnClickListener((v) -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(DONATE_URL)));
            }
            catch (android.content.ActivityNotFoundException e) {
                AppUtils.showToast(this, DONATE_URL);
            }
        });

        btUpdate.setAllCaps(false);
        // Touch only: the controller's A must keep starting the game.
        btUpdate.setFocusable(false);
        btUpdate.setOnClickListener((v) -> onUpdate());
        checkForUpdate();

        if (!requestAppPermissions()) RootFSInstaller.installIfNeeded(this);
    }

    /** Looks for a newer release on GitHub; the Update button appears above Play if there is one. */
    private void checkForUpdate() {
        String current = com.winlator.BuildConfig.VERSION_NAME;
        Executors.newSingleThreadExecutor().execute(() -> {
            UpdateChecker.Release release = UpdateChecker.findNewer(current);
            if (release == null) return;
            runOnUiThread(() -> {
                update = release;
                btUpdate.setText(getString(R.string.wow_update, release.version));
                btUpdate.setVisibility(View.VISIBLE);
                // A keeps starting the game: Play stays the focused button.
                focusPlay();
            });
        });
    }

    /** Shows what's new since the installed version; Install downloads and installs it. */
    private void onUpdate() {
        if (update == null || downloading) return;
        if (updateApk != null) {
            installUpdate();
            return;
        }
        // Same look as the launcher: dark panel, gold trim, cream serif text.
        app.gamenative.externaldisplay.PadTheme theme = app.gamenative.externaldisplay.PadTheme.Companion.getDEFAULT();
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * density);
        TextView title = new TextView(this);
        title.setText(getString(R.string.wow_update_title, update.version));
        title.setTextSize(20f);
        title.setTextColor(theme.getText());
        title.setTypeface(theme.getTypeface());
        title.setPadding(pad, pad, pad, pad / 2);
        TextView notes = new TextView(this);
        notes.setText(update.changelog.isEmpty() ? getString(R.string.wow_update_no_notes) : update.changelog);
        notes.setTextSize(14f);
        notes.setTextColor(theme.getText());
        notes.setLineSpacing(0f, 1.15f);
        notes.setPadding(pad, 0, pad, pad / 2);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(notes);
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
            .setCustomTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.wow_update_install_button, (d, w) -> downloadUpdate())
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            android.graphics.drawable.GradientDrawable panel = new android.graphics.drawable.GradientDrawable();
            panel.setColor(0xFF000000 | theme.getBackground());
            panel.setCornerRadius(10 * density);
            panel.setStroke(Math.round(2 * density), theme.getBorder());
            dialog.getWindow().setBackgroundDrawable(panel);
        }
        for (int which : new int[]{android.content.DialogInterface.BUTTON_POSITIVE, android.content.DialogInterface.BUTTON_NEGATIVE}) {
            Button b = dialog.getButton(which);
            if (b == null) continue;
            b.setTextColor(theme.getText());
            b.setTypeface(theme.getTypeface());
        }
        Button install = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (install != null) install.requestFocus();
    }

    /** Downloads the new version (progress on the button), then opens Android's installer. */
    private void downloadUpdate() {
        if (update == null || downloading) return;
        if (updateApk != null) {
            installUpdate();
            return;
        }
        downloading = true;
        btUpdate.setText(getString(R.string.wow_update_downloading, 0));
        UpdateChecker.Release release = update;
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                java.io.File apk = UpdateChecker.download(this, release, (percent) -> runOnUiThread(() ->
                    btUpdate.setText(percent >= 0 ? getString(R.string.wow_update_downloading, percent) : getString(R.string.wow_update_downloading_nosize))));
                runOnUiThread(() -> {
                    downloading = false;
                    updateApk = apk;
                    installUpdate();
                });
            }
            catch (Exception e) {
                runOnUiThread(() -> {
                    downloading = false;
                    btUpdate.setText(getString(R.string.wow_update_failed));
                });
            }
        });
    }

    private void installUpdate() {
        awaitingInstallPermission = false;
        if (UpdateChecker.install(this, updateApk)) btUpdate.setText(getString(R.string.wow_update_install, update.version));
        else {
            awaitingInstallPermission = true;
            btUpdate.setText(getString(R.string.wow_update_allow));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MainActivity.PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                RootFSInstaller.installIfNeeded(this);
                updateStatus();
            }
            else finish();
        }
    }

    /**
     * Controller: the D-pad / left stick move the focus border (focus_border) between the
     * buttons, A presses the focused one (Play when nothing else is focused), Start always
     * presses Play.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_BUTTON_A && code != KeyEvent.KEYCODE_BUTTON_START) return super.dispatchKeyEvent(event);
        boolean up = event.getAction() == KeyEvent.ACTION_UP && event.getRepeatCount() == 0;
        View focus = getCurrentFocus();
        if (code == KeyEvent.KEYCODE_BUTTON_A && focus instanceof Button && focus != btPlay) {
            if (up) focus.performClick();
            return true;
        }
        if (up && btPlay.isEnabled() && !preloaderDialog.isShowing()) onPlay();
        return true;
    }

    /** The main screen's backdrop: a picture scaled to cover the screen (cropped, not stretched),
     *  darkened by [scrim] so the text and buttons stay readable. */
    private static final class BackdropDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Bitmap bitmap;
        private final int scrim;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        private final android.graphics.Rect src = new android.graphics.Rect();

        BackdropDrawable(android.graphics.Bitmap bitmap, int scrim) {
            this.bitmap = bitmap;
            this.scrim = scrim;
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            canvas.drawColor(scrim | 0xFF000000);
            if (bitmap != null && b.width() > 0 && b.height() > 0) {
                float scale = Math.max(b.width() / (float)bitmap.getWidth(), b.height() / (float)bitmap.getHeight());
                int w = Math.round(b.width() / scale), h = Math.round(b.height() / scale);
                int x = (bitmap.getWidth() - w) / 2, y = (bitmap.getHeight() - h) / 2;
                src.set(x, y, x + w, y + h);
                canvas.drawBitmap(bitmap, src, b, paint);
            }
            canvas.drawColor(scrim);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    /** The pad's button style in Dalaran colours (see PadTheme.LAUNCHER): pale gold on stone. */
    private void applyPadStyle() {
        app.gamenative.externaldisplay.PadTheme theme = app.gamenative.externaldisplay.PadTheme.LAUNCHER;
        float density = getResources().getDisplayMetrics().density;
        findViewById(R.id.LLWowRoot).setBackground(new BackdropDrawable(
                android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.launcher_dalaran),
                (theme.getBackground() & 0x00FFFFFF) | 0x99000000));
        findViewById(R.id.LLWowStatusPanel).setBackground(theme.groupBackground(density, false));
        for (int id : new int[]{R.id.TVWowTitle, R.id.TVWowStatus}) {
            TextView tv = findViewById(id);
            tv.setTextColor(theme.getText());
            tv.setTypeface(theme.getTypeface());
        }
        for (int id : new int[]{R.id.TVWowSubtitle, R.id.TVWowFolder, R.id.TVWowRealmlist}) {
            ((TextView)findViewById(id)).setTextColor((theme.getText() & 0x00FFFFFF) | 0xB0000000);
        }
        styleWowRedButton(findViewById(R.id.BTUpdate), density, theme.getTypeface());
        for (int id : new int[]{R.id.BTPlay, R.id.BTSelectFolder, R.id.BTDonate}) {
            Button b = findViewById(id);
            android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
            // Change Game Folder and Donate: dim bronze trim, so Play stands out.
            Integer trim = id == R.id.BTPlay ? null : Integer.valueOf(0xFF6E6250);
            states.addState(new int[]{android.R.attr.state_pressed}, theme.buttonBackground(density, 10f, true, false, false, false, false, trim, 1f));
            states.addState(new int[]{}, theme.buttonBackground(density, 10f, false, false, false, id == R.id.BTPlay, false, trim, 1f));
            b.setBackgroundTintList(null);
            b.setBackground(states);
            b.setStateListAnimator(null);
            b.setTextColor(theme.getText());
            b.setTypeface(theme.getTypeface());
        }
        for (int id : new int[]{R.id.BTAdvanced, R.id.BTWowSettings, R.id.BTContainerSettings}) {
            Button b = findViewById(id);
            b.setTextColor((theme.getText() & 0x00FFFFFF) | 0xB0000000);
            b.setTypeface(theme.getTypeface());
        }
    }

    /**
     * The red button of WoW's login and character screens ("Enter World"): a dark grey bevelled
     * frame around a red fill that is darker at the top, with cream text.
     */
    private static void styleWowRedButton(Button b, float density, android.graphics.Typeface typeface) {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, wowRed(density, 0xFF3E0804, 0xFF6E120A, 0xFF8C2214));
        states.addState(new int[]{}, wowRed(density, 0xFF5C0C06, 0xFF9A190D, 0xFFC0301C));
        b.setBackgroundTintList(null);
        b.setBackground(states);
        b.setStateListAnimator(null);
        b.setTextColor(0xFFFFF0DC);
        b.setTypeface(typeface);
        b.setShadowLayer(2f * density, 0f, 1f * density, 0xFF1A0000);
    }

    private static android.graphics.drawable.Drawable wowRed(float density, int top, int middle, int bottom) {
        float radius = 4f * density;
        android.graphics.drawable.GradientDrawable outer = new android.graphics.drawable.GradientDrawable();
        outer.setColor(0xFF1C1A19);
        outer.setCornerRadius(radius);
        android.graphics.drawable.GradientDrawable frame = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF8E8A86, 0xFF4A4644, 0xFF6E6A66});
        frame.setCornerRadius(radius);
        android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, middle, bottom});
        fill.setCornerRadius(radius * 0.6f);
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[]{outer, frame, fill});
        int o = Math.round(1.5f * density), f = Math.round(4f * density);
        layers.setLayerInset(1, o, o, o, o);
        layers.setLayerInset(2, f, f, f, f);
        return layers;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Focus asked for before the window had it doesn't show: ask again now, so Play is
        // highlighted from the start and it's clear A starts the game.
        if (hasFocus) btPlay.post(this::focusPlay);
    }

    /** Shows the focus border on Play, so it's clear A starts the game. */
    private void focusPlay() {
        if (btPlay.isEnabled()) btPlay.requestFocusFromTouch();
        else findViewById(R.id.BTSelectFolder).requestFocusFromTouch();
    }

    private boolean requestAppPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return false;

        String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
        ActivityCompat.requestPermissions(this, permissions, MainActivity.PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingInstallPermission && updateApk != null && (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls())) {
            installUpdate();
        }
        updateStatus();
        focusPlay();
        // Pick up phase-B provisioning after the guided first login + logout
        GameFolder gameFolder = getGameFolder();
        if (gameFolder != null && gameFolder.isValid()) {
            Executors.newSingleThreadExecutor().execute(() -> {
                Provisioner provisioner = new Provisioner(this, gameFolder);
                if (provisioner.getStatus() == Provisioner.Status.PENDING) {
                    provisioner.provision();
                    runOnUiThread(this::updateStatus);
                }
            });
        }
    }

    public GameFolder getGameFolder() {
        String path = preferences.getString(PREF_GAME_FOLDER, null);
        return path != null ? new GameFolder(path) : null;
    }

    private void showStatus(int text) {
        tvStatus.setText(text);
        tvStatus.setVisibility(View.VISIBLE);
    }

    private void updateStatus() {
        GameFolder gameFolder = getGameFolder();
        ((Button)findViewById(R.id.BTSelectFolder)).setText(gameFolder == null ? R.string.wow_select_game_folder : R.string.wow_change_game_folder);

        if (gameFolder == null) {
            tvFolder.setText(R.string.wow_no_folder_selected);
            showStatus(R.string.wow_status_select_folder);
            tvRealmlist.setText("");
            btPlay.setEnabled(false);
            return;
        }

        tvFolder.setText(gameFolder.root.getPath());

        if (!gameFolder.isValid()) {
            showStatus(R.string.wow_status_invalid_folder);
            tvRealmlist.setText("");
            btPlay.setEnabled(false);
            return;
        }

        Provisioner provisioner = new Provisioner(this, gameFolder);
        tvRealmlist.setText(getString(R.string.wow_realm_server)+": "+provisioner.getActiveRealmlist());
        btPlay.setEnabled(true);

        switch (provisioner.getStatus()) {
            case PENDING:
                showStatus(R.string.wow_status_pending);
                break;
            case WAITING_FIRST_LOGIN: // the account folder is set up during the first login (AccountWatcher)
            case READY:
                tvStatus.setVisibility(View.GONE); // nothing to say when all is set up
                break;
            default:
                showStatus(R.string.wow_status_invalid_folder);
                break;
        }
    }

    private void onPlay() {
        GameFolder gameFolder = getGameFolder();
        if (gameFolder == null || !gameFolder.isValid()) {
            showFolderPicker();
            return;
        }

        preloaderDialog.show(R.string.wow_preparing_game);
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            // Keep our installed addons in step with this app (only their own folders).
            Provisioner provisioner = new Provisioner(this, gameFolder);
            provisioner.setAccountName(LoginSecret.accountName(this));
            AddonInstaller addons = new AddonInstaller(this, gameFolder);
            // Put back any that are missing (not in a WoW Imito folder: its addons need its own ConsolePort).
            if (!provisioner.isWowImitoFolder()) addons.installMissing();
            addons.updateInstalled();
            boolean provisioned = provisioner.provision();

            handler.post(() -> {
                if (!provisioned) {
                    preloaderDialog.close();
                    AppUtils.showToast(this, R.string.wow_provision_failed);
                    return;
                }

                WowContainerHelper helper = new WowContainerHelper(this);
                helper.ensureContainerAsync(gameFolder, (container) -> {
                    preloaderDialog.close();
                    if (container == null) {
                        AppUtils.showToast(this, R.string.wow_container_failed);
                        return;
                    }
                    File shortcutFile = helper.ensureShortcut(container, gameFolder);
                    startActivity(helper.createLaunchIntent(container, shortcutFile));
                });
            });
        });
    }

    /**
     * After a game folder is chosen: lists the addons this app will install and, on
     * Continue, zips Interface/AddOns into the game folder and installs them.
     */
    private void offerAddons(GameFolder gameFolder) {
        AddonInstaller installer = new AddonInstaller(this, gameFolder);
        StringBuilder list = new StringBuilder();
        for (String name : installer.plan()) list.append("\n  •  ").append(name);
        String message = getString(R.string.wow_addons_message, list.toString());
        java.util.List<String> moved = installer.wowImitoAddonsToDisable();
        if (!moved.isEmpty()) message += "\n\n" + getString(R.string.wow_addons_imito, android.text.TextUtils.join(", ", moved), AddonInstaller.DISABLED_DIR);
        // The message, then the "recommended addons" checkbox (off unless ticked) and its note.
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);
        android.widget.LinearLayout body = new android.widget.LinearLayout(this);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);
        body.setPadding(pad, (int) (12 * density), pad, 0);
        android.widget.TextView text = new android.widget.TextView(this);
        text.setText(message);
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        body.addView(text);
        android.widget.CheckBox recommended = new android.widget.CheckBox(this);
        recommended.setText(R.string.wow_addons_recommended);
        recommended.setPadding(0, (int) (8 * density), 0, 0);
        body.addView(recommended);
        android.widget.TextView note = new android.widget.TextView(this);
        note.setText(R.string.wow_addons_recommended_note);
        note.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        note.setAlpha(0.75f);
        body.addView(note);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.wow_addons_title)
            .setView(scroll)
            .setPositiveButton(R.string.wow_addons_continue, (d, w) -> installAddons(installer, recommended.isChecked()))
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void installAddons(AddonInstaller installer, boolean withRecommended) {
        preloaderDialog.show(R.string.wow_addons_installing);
        Executors.newSingleThreadExecutor().execute(() -> {
            String message;
            try {
                File backup = installer.backup();
                installer.install();
                message = backup != null ? getString(R.string.wow_addons_done_backup, backup.getName()) : getString(R.string.wow_addons_done);
                if (withRecommended) {
                    try {
                        installer.installRecommended(percent -> runOnUiThread(() ->
                            preloaderDialog.setText(getString(R.string.wow_addons_downloading, percent))));
                    }
                    catch (Exception e) {
                        message = getString(R.string.wow_addons_recommended_failed);
                    }
                }
            }
            catch (Exception e) {
                message = getString(R.string.wow_addons_failed);
            }
            final String result = message;
            runOnUiThread(() -> {
                preloaderDialog.close();
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage(result)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            });
        });
    }

    private void showFolderPicker() {
        GameFolder current = getGameFolder();
        File startDir = current != null && current.root.isDirectory() ?
            current.root : Environment.getExternalStorageDirectory();
        showFolderPickerAt(startDir);
    }

    private void showFolderPickerAt(final File dir) {
        final ArrayList<File> entries = new ArrayList<>();
        final ArrayList<String> names = new ArrayList<>();

        File parent = dir.getParentFile();
        boolean hasParent = parent != null && !dir.equals(Environment.getExternalStorageDirectory()) && parent.canRead();
        if (hasParent) {
            entries.add(parent);
            names.add("..");
        }

        File[] files = dir.listFiles();
        if (files != null) {
            ArrayList<File> dirs = new ArrayList<>();
            for (File file : files) if (file.isDirectory() && !file.getName().startsWith(".")) dirs.add(file);
            dirs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File file : dirs) {
                entries.add(file);
                names.add(file.getName());
            }
        }

        GameFolder candidate = new GameFolder(dir);
        String title = dir.getPath().replace(Environment.getExternalStorageDirectory().getPath(), getString(R.string.internal_storage));
        if (candidate.isValid()) title += " ✓";

        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle(title);

        ListView listView = new ListView(this);
        listView.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        builder.setView(listView);

        if (candidate.isValid()) {
            builder.setPositiveButton(R.string.wow_use_this_folder, (d, w) -> {
                preferences.edit().putString(PREF_GAME_FOLDER, dir.getPath()).apply();
                updateStatus();
                offerAddons(candidate);
            });
        }
        builder.setNegativeButton(R.string.cancel, (d, w) -> {});

        final AlertDialog dialog = builder.create();
        listView.setOnItemClickListener((adapterView, view, position, id) -> {
            dialog.dismiss();
            showFolderPickerAt(entries.get(position));
        });
        dialog.show();
    }
}
