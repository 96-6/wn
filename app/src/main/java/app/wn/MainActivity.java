package app.wn;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import app.wn.audio.MixerConfig;
import java.util.List;

/** Mixer only. Profiles and profiling live on separate functional screens. */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int[] widthIds = {R.id.width_mono, R.id.width_narrow, R.id.width_default, R.id.width_wide};
    private SettingsStore settings;
    private LinearLayout generators;
    private Button play;
    private TextView status, masterValue;
    private SeekBar master;
    private RadioGroup widths;
    private boolean restoring;
    private String structure = "";
    private final Runnable update = () -> {
        if (PlaybackStatus.active) getApplicationContext().startService(new Intent(this, NoiseService.class).setAction(NoiseService.UPDATE));
    };
    private final Runnable poll = new Runnable() {
        @Override public void run() { refresh(false); handler.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        settings = SettingsStore.get(this);
        LinearLayout body = Ui.screen(this, "WN", false);
        Button profiles = Ui.button(this, "Profiles", false); profiles.setOnClickListener(v -> openProfiles());
        Ui.add(this, body, profiles, 12);
        play = Ui.button(this, "Start", true); play.setOnClickListener(v -> toggle());
        Ui.add(this, body, play, 12);
        status = Ui.text(this, "Stopped", 12, Ui.MUTED, false); status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); Ui.add(this, body, status, 8);
        generators = Ui.column(this); body.addView(generators);
        Button add = Ui.button(this, "Add generator", false);
        add.setOnClickListener(v -> chooseProfile(id -> { settings.addGenerator(id); changed(); renderGenerators(); }));
        Ui.add(this, body, add, 14);

        LinearLayout out = Ui.card(this, body);
        LinearLayout levelRow = Ui.row(this);
        levelRow.addView(Ui.text(this, "Master", 15, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        masterValue = Ui.text(this, "", 14, Ui.ACCENT, true); levelRow.addView(masterValue); out.addView(levelRow);
        master = slider("Master level"); out.addView(master);
        master.setOnSeekBarChangeListener(listener((seek, value) -> {
            masterValue.setText(getString(R.string.level_percent, value));
            if (!restoring) { settings.output(value / 100.0, selectedWidth()); changed(); }
        }));
        Ui.add(this, out, Ui.text(this, "Stereo width", 15, Ui.INK, true), 10);
        widths = new RadioGroup(this); widths.setOrientation(RadioGroup.HORIZONTAL);
        String[] names = {"Mono", "Narrow", "Default", "Wide"};
        for (int i = 0; i < 4; i++) {
            RadioButton b = new RadioButton(this); b.setId(widthIds[i]); b.setText(names[i]);
            b.setButtonDrawable(null); b.setTextSize(12); b.setGravity(Gravity.CENTER); b.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1);
            if (i > 0) lp.leftMargin = Ui.dp(this, 5); widths.addView(b, lp);
        }
        Ui.add(this, out, widths, 10);
        widths.setOnCheckedChangeListener((group, id) -> {
            paintWidths();
            if (!restoring) { settings.output(master.getProgress() / 100.0, selectedWidth()); changed(); }
        });
        Button battery = Ui.button(this, "Battery settings", false);
        battery.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
            catch (RuntimeException e) { Ui.error(this, "Battery settings", "Open Android Settings → Apps → WN → Battery."); }
        });
        Ui.add(this, body, battery, 14);
        Ui.add(this, body, Ui.text(this, "Allow unrestricted battery use if Android interrupts playback.", 11, Ui.MUTED, false), 8);
    }

    private void renderGenerators() {
        restoring = true; generators.removeAllViews();
        List<SettingsStore.Layer> layers = settings.layers(); int number = 1;
        for (SettingsStore.Layer layer : layers) {
            SettingsStore.SavedProfile profile = layer.profileId == null ? null : settings.findProfile(layer.profileId);
            String name = profile == null ? "Default" : profile.preset.name;
            LinearLayout card = Ui.card(this, generators), header = Ui.row(this);
            TextView title = Ui.text(this, number++ + "  " + name, 18, Ui.INK, true);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            Switch enabled = new Switch(this); enabled.setChecked(layer.enabled); enabled.setMinHeight(Ui.dp(this, 48));
            enabled.setContentDescription("Enable " + name); header.addView(enabled); card.addView(header);
            if ("default".equals(layer.id)) {
                SpectrumView view = new SpectrumView(this, app.wn.audio.NoisePreset.DEFAULT);
                card.addView(view, new LinearLayout.LayoutParams(-1, Ui.dp(this, 106)));
            } else {
                LinearLayout actions = Ui.row(this);
                Button choose = Ui.button(this, "Change profile", false), remove = Ui.button(this, "Remove", false);
                actions.addView(choose, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1); lp.leftMargin = Ui.dp(this, 8);
                actions.addView(remove, lp); Ui.add(this, card, actions, 8);
                choose.setOnClickListener(v -> chooseProfile(id -> { settings.updateGenerator(layer.id, null, null, id); changed(); renderGenerators(); }));
                remove.setOnClickListener(v -> { settings.removeGenerator(layer.id); changed(); renderGenerators(); });
            }
            if (profile != null && profile.preset.motion != null) {
                String[] types = {"Continuous noise", "Oscillating noise + tone"};
                Button type = Ui.button(this, "Type: " + types[layer.texture.ordinal()], false);
                Ui.add(this, card, type, 8);
                type.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Generator type")
                        .setSingleChoiceItems(types, layer.texture.ordinal(), (dialog, which) -> {
                            try {
                                settings.texture(layer.id, MixerConfig.Texture.values()[which]);
                                changed(); dialog.dismiss(); renderGenerators();
                            } catch (RuntimeException e) { Ui.error(this, "Generator type", e.getMessage()); }
                        }).setNegativeButton("Cancel", null).show());
                if(layer.texture==MixerConfig.Texture.OSCILLATING) {
                    Button fine=Ui.button(this,"Fine controls & preview",false);Ui.add(this,card,fine,8);
                    fine.setOnClickListener(v->startActivity(new Intent(this,OscillationActivity.class).putExtra("generator",layer.id)));
                }
            }
            LinearLayout label = Ui.row(this);
            label.addView(Ui.text(this, "Level", 13, Ui.INK, false), new LinearLayout.LayoutParams(0, -2, 1));
            TextView value = Ui.text(this, getString(R.string.level_percent, (int) Math.round(layer.level * 100)), 13, Ui.ACCENT, true);
            label.addView(value); Ui.add(this, card, label, 10);
            SeekBar gain = slider(name + " level"); gain.setProgress((int) Math.round(layer.level * 100)); card.addView(gain);
            enabled.setOnCheckedChangeListener((v, checked) -> { if (!restoring) { settings.updateGenerator(layer.id, checked, null, null); changed(); } });
            gain.setOnSeekBarChangeListener(listener((seek, level) -> {
                value.setText(getString(R.string.level_percent, level));
                if (!restoring) { settings.updateGenerator(layer.id, null, level / 100.0, null); changed(); }
            }));
        }
        structure = structureKey(); restoring = false;
    }
    private String structureKey() {
        StringBuilder key = new StringBuilder();
        for (SettingsStore.Layer l : settings.layers()) {
            SettingsStore.SavedProfile p = l.profileId == null ? null : settings.findProfile(l.profileId);
            key.append(l.id).append(':').append(l.profileId).append(':').append(p == null ? "" : p.preset.name).append(':').append(l.texture).append('|');
        }
        return key.toString();
    }
    private interface Selected { void use(String id); }
    private void chooseProfile(Selected selected) {
        List<SettingsStore.SavedProfile> saved = settings.profiles();
        if (saved.isEmpty()) { openProfiles(); return; }
        String[] names = new String[saved.size()];
        for (int i = 0; i < names.length; i++) names[i] = saved.get(i).preset.name;
        new AlertDialog.Builder(this).setTitle("Saved profiles").setItems(names, (dialog, which) -> {
            try { selected.use(saved.get(which).id); } catch (RuntimeException e) { Ui.error(this, "Generator", e.getMessage()); }
        }).setNeutralButton("Manage profiles", (dialog, which) -> openProfiles()).setNegativeButton("Cancel", null).show();
    }
    private void openProfiles() { startActivity(new Intent(this, ProfilesActivity.class)); }
    private void changed() { handler.removeCallbacks(update); handler.postDelayed(update, 60); }
    private MixerConfig.Width selectedWidth() {
        for (int i = 0; i < widthIds.length; i++) if (widths.getCheckedRadioButtonId() == widthIds[i]) return MixerConfig.Width.values()[i];
        return MixerConfig.Width.DEFAULT;
    }
    private void paintWidths() {
        for (int i = 0; i < 4; i++) {
            RadioButton b = (RadioButton) widths.getChildAt(i);
            b.setTextColor(b.isChecked() ? Ui.BG : Ui.MUTED); b.setBackground(Ui.shape(this, b.isChecked() ? Ui.ACCENT : 0xff20313a));
        }
    }
    private SeekBar slider(String description) {
        SeekBar b = new SeekBar(this); b.setMax(100); b.setContentDescription(description);
        b.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT)); b.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
        b.setLayoutParams(new LinearLayout.LayoutParams(-1, Ui.dp(this, 48))); return b;
    }
    private interface Progress { void change(SeekBar seek, int progress); }
    private SeekBar.OnSeekBarChangeListener listener(Progress callback) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int progress, boolean user) { callback.change(b, progress); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        };
    }
    private void toggle() {
        if (PlaybackStatus.active) { startService(new Intent(this, NoiseService.class).setAction(NoiseService.STOP)); return; }
        if (!settings.snapshot().hasAudibleLayer()) { Toast.makeText(this, "Enable a generator and raise its level and Master.", Toast.LENGTH_LONG).show(); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notifications_asked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 102); return;
        }
        startNoise();
    }
    private void startNoise() {
        try { startForegroundService(new Intent(this, NoiseService.class).setAction(NoiseService.START)); }
        catch (RuntimeException e) { Ui.error(this, "Playback", e.getMessage()); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results); if (request == 102) startNoise();
    }
    private void refresh(boolean all) {
        if (all || !structure.equals(structureKey())) renderGenerators();
        if (all) {
            restoring = true; MixerConfig c = settings.snapshot();
            master.setProgress((int) Math.round(c.master * 100));
            masterValue.setText(getString(R.string.level_percent, master.getProgress()));
            widths.check(widthIds[c.width.ordinal()]); paintWidths(); restoring = false;
        }
        String action = PlaybackStatus.active ? "Stop" : "Start";
        if (!play.getText().toString().equals(action)) { play.setText(action); play.setContentDescription(action + " noise"); }
        String message = PlaybackStatus.error.isEmpty() ? PlaybackStatus.message : PlaybackStatus.error;
        if (!status.getText().toString().equals(message)) status.setText(message);
    }
    @Override protected void onResume() { super.onResume(); refresh(true); update.run(); handler.removeCallbacks(poll); handler.post(poll); }
    @Override protected void onPause() { handler.removeCallbacks(poll); handler.removeCallbacks(update); update.run(); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacks(poll); handler.removeCallbacks(update); super.onDestroy(); }
}
