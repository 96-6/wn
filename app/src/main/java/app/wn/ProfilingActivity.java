package app.wn;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import app.wn.audio.SpectrumProfiler;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/** User-started local profiling. Microphone capture is cancelled when this screen leaves the foreground. */
public final class ProfilingActivity extends ProfileExchangeActivity {
    private static final int MICROPHONE = 610, AUDIO_FILE = 611;
    private EditText name, start, duration;
    private Spinner detail;
    private Button record, file, stop, add, export;
    private TextView status, resultText;
    private ProgressBar progress;
    private volatile AndroidProfiler.Control control;
    private boolean busy, recording, resumed, pendingMic, autoOpened;
    private String savedId;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = Ui.screen(this, "Create profile", true);
        Ui.add(this, body, Ui.text(this, "Microphone recording stops noise playback. Profiles save automatically.", 13, Ui.MUTED, false), 12);
        name = input(body, "Profile name", InputType.TYPE_CLASS_TEXT,
                state == null ? "Profile " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()) : state.getString("name", ""));
        duration = input(body, "Duration (seconds, 1–120)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL,
                state == null ? "15" : state.getString("duration", "15"));
        start = input(body, "File start (seconds)", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL,
                state == null ? "0" : state.getString("start", "0"));
        Ui.add(this, body, Ui.text(this, "Band selection", 13, Ui.INK, true), 12);
        detail = new Spinner(this); detail.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Narrow", "Balanced", "Broad"})); detail.setSelection(state == null ? 1 : state.getInt("detail", 1));
        Ui.add(this, body, detail, 8);
        record = Ui.button(this, "Record microphone", true); file = Ui.button(this, "Choose audio file", false);
        stop = Ui.button(this, "Finish recording", false); stop.setEnabled(false);
        record.setOnClickListener(v -> requestMic()); file.setOnClickListener(v -> chooseAudio());
        stop.setOnClickListener(v -> { if (control != null) { control.finish = true; stop.setEnabled(false); } });
        Ui.add(this, body, record, 16); Ui.add(this, body, file, 8); Ui.add(this, body, stop, 8);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100);
        Ui.add(this, body, progress, 16);
        status = Ui.text(this, "Ready", 14, Ui.MUTED, false); Ui.add(this, body, status, 8);
        resultText = Ui.text(this, "", 14, Ui.INK, false); Ui.add(this, body, resultText, 12);
        add = Ui.button(this, "Add generator", true); export = Ui.button(this, "Export JSON", false);
        add.setVisibility(android.view.View.GONE); export.setVisibility(android.view.View.GONE);
        add.setOnClickListener(v -> { try { settings.addGenerator(savedId); openMixer(); } catch (RuntimeException e) { Ui.error(this, "Generator", e.getMessage()); } });
        export.setOnClickListener(v -> {
            SettingsStore.SavedProfile p = settings.findProfile(savedId); if (p != null) exportJson(p.preset);
        });
        Ui.add(this, body, add, 16); Ui.add(this, body, export, 8);
        if (state != null) autoOpened = state.getBoolean("auto", false);
    }
    private EditText input(LinearLayout body, String label, int type, String value) {
        Ui.add(this, body, Ui.text(this, label, 13, Ui.INK, true), 12);
        EditText edit = new EditText(this); edit.setTextColor(Ui.INK); edit.setInputType(type); edit.setSingleLine(true);
        edit.setText(value); edit.setMinHeight(Ui.dp(this, 48)); edit.setContentDescription(label); Ui.add(this, body, edit, 2); return edit;
    }
    private double seconds() {
        double n = Double.parseDouble(duration.getText().toString());
        if (!Double.isFinite(n) || n < 1 || n > 120) throw new IllegalArgumentException("Duration must be 1–120 seconds.");
        return n;
    }
    private double offset() {
        double n = Double.parseDouble(start.getText().toString());
        if (!Double.isFinite(n) || n < 0 || n > 86400) throw new IllegalArgumentException("File start must be 0–86400 seconds.");
        return n;
    }
    private String profileName() {
        String s = name.getText().toString().trim();
        if (s.isEmpty() || s.length() > 80) throw new IllegalArgumentException("Use a profile name with 1–80 characters.");
        return s;
    }
    private SpectrumProfiler.Tuning tuning() {
        return detail.getSelectedItemPosition() == 0 ? SpectrumProfiler.Tuning.narrow()
                : detail.getSelectedItemPosition() == 2 ? SpectrumProfiler.Tuning.broad() : SpectrumProfiler.Tuning.balanced();
    }
    private void requestMic() {
        if (busy) return;
        try { seconds(); profileName(); } catch (RuntimeException e) { Ui.error(this, "Profile", e.getMessage()); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE); return;
        }
        if (resumed) begin(null);
        else pendingMic = true;
    }
    private void chooseAudio() {
        if (busy) return;
        try {
            seconds(); offset(); profileName();
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), AUDIO_FILE);
        } catch (RuntimeException e) { Ui.error(this, "Audio file", e.getMessage()); }
    }
    private void begin(android.net.Uri uri) {
        if (busy) return;
        final String title; final double length, beginAt; final SpectrumProfiler.Tuning settings;
        try { title = profileName(); length = seconds(); beginAt = uri == null ? 0 : offset(); settings = tuning(); }
        catch (RuntimeException e) { Ui.error(this, "Profile", e.getMessage()); return; }
        recording = uri == null; control = new AndroidProfiler.Control();
        if (recording && PlaybackStatus.active) startService(new Intent(this, NoiseService.class).setAction(NoiseService.STOP));
        AndroidProfiler.Control operation = control;
        setBusy(true); status.setText(recording ? "Recording…" : "Analyzing…"); progress.setProgress(0);
        io.execute(() -> {
            try {
                if (uri == null) {
                    long waitUntil = android.os.SystemClock.elapsedRealtime() + 5000;
                    while (PlaybackStatus.playing && android.os.SystemClock.elapsedRealtime() < waitUntil) {
                        AndroidProfiler.check(operation); android.os.SystemClock.sleep(10);
                    }
                    if (PlaybackStatus.playing) throw new IllegalStateException("Stop noise playback before recording.");
                }
                AndroidProfiler.Progress update = done -> main.post(() -> {
                    if (!isDestroyed() && !operation.cancelled) {
                        progress.setProgress((int) Math.min(100, done * 100 / length));
                        status.setText(String.format(Locale.getDefault(), "%.1f / %.1f s", done, length));
                    }
                });
                SpectrumProfiler.Result result = uri == null ? AndroidProfiler.microphone(title, length, settings, operation, update)
                        : AndroidProfiler.audioFile(getApplicationContext(), uri, title, beginAt, length, settings, operation, update);
                AndroidProfiler.check(operation);
                String id = this.settings.addProfile(result.profile);
                String savedName = this.settings.findProfile(id).preset.name;
                main.post(() -> {
                    if (isDestroyed()) return;
                    savedId = id; setBusy(false); progress.setProgress(100); status.setText(R.string.profile_saved);
                    resultText.setText(String.format(Locale.getDefault(), "%s\n%d bands · %.0f Hz selected\n%.0f%% of in-range energy retained",
                            savedName, result.profile.frequencyBands().length, result.bandwidthHz, result.energyCoverage * 100));
                    add.setVisibility(android.view.View.VISIBLE); export.setVisibility(android.view.View.VISIBLE);
                });
            } catch (CancellationException e) {
                main.post(() -> { if (!isDestroyed()) { setBusy(false); status.setText(R.string.profile_cancelled); } });
            } catch (Exception e) {
                main.post(() -> { if (!isDestroyed()) { setBusy(false); status.setText(R.string.profile_not_created); Ui.error(this, "Profiling failed", e.getMessage()); } });
            }
        });
    }
    private void setBusy(boolean value) {
        busy = value; record.setEnabled(!value); file.setEnabled(!value); stop.setEnabled(value && recording);
        name.setEnabled(!value); duration.setEnabled(!value); start.setEnabled(!value); detail.setEnabled(!value);
        add.setEnabled(!value); export.setEnabled(!value);
        if (value && recording) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MICROPHONE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) { pendingMic = true; if (resumed) { pendingMic = false; begin(null); } }
            else status.setText(R.string.microphone_denied);
        }
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == AUDIO_FILE && result == RESULT_OK && data != null && data.getData() != null) begin(data.getData());
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (pendingMic) { pendingMic = false; begin(null); }
        if (!autoOpened) {
            autoOpened = true; String source = getIntent().getStringExtra("source");
            if ("mic".equals(source)) main.post(this::requestMic);
            else if ("file".equals(source)) main.post(this::chooseAudio);
        }
    }
    @Override protected void onPause() {
        resumed = false;
        if (busy && recording && control != null) control.cancelled = true;
        super.onPause();
    }
    @Override protected void onDestroy() { if (control != null) control.cancelled = true; super.onDestroy(); }
    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); state.putString("name", name.getText().toString()); state.putString("duration", duration.getText().toString());
        state.putString("start", start.getText().toString()); state.putInt("detail", detail.getSelectedItemPosition()); state.putBoolean("auto", autoOpened);
    }
}
