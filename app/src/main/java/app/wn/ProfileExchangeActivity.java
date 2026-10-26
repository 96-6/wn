package app.wn;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import app.wn.audio.NoisePreset;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared JSON file picker; the URI is never needed after the validated private copy is saved. */
public abstract class ProfileExchangeActivity extends Activity {
    protected SettingsStore settings;
    protected final Handler main = new Handler(Looper.getMainLooper());
    protected final ExecutorService io = Executors.newSingleThreadExecutor();
    private String exportText;
    private static final int IMPORT = 501, EXPORT = 502;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); settings = SettingsStore.get(this);
        if (state != null) exportText = state.getString("export");
    }
    protected void importJson() {
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT); }
        catch (RuntimeException e) { Ui.error(this, "Import JSON", "Android's Files app is unavailable."); }
    }
    protected void exportJson(NoisePreset profile) {
        exportText = profile.toJson();
        String filename = profile.name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (filename.length() > 64) filename = filename.substring(0, 64);
        try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, filename + ".json"), EXPORT); }
        catch (RuntimeException e) { Ui.error(this, "Export JSON", "Android's Files app is unavailable."); }
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == IMPORT) io.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                NoisePreset profile = ProfileFiles.read(input);
                String id = settings.addProfile(profile);
                main.post(() -> { if (!isDestroyed()) { onProfileImported(id); Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show(); } });
            } catch (Exception e) { main.post(() -> Ui.error(this, "Import failed", e.getMessage())); }
        });
        if (request == EXPORT && exportText != null) {
            String text = exportText;
            io.execute(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IllegalStateException("Could not open the destination.");
                    output.write(text.getBytes(StandardCharsets.UTF_8));
                    main.post(() -> { if (!isDestroyed()) Toast.makeText(this, "Profile exported", Toast.LENGTH_SHORT).show(); });
                } catch (Exception e) { main.post(() -> Ui.error(this, "Export failed", e.getMessage())); }
            });
        }
    }
    protected void onProfileImported(String id) { }
    @Override public void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("export", exportText); }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
    protected void openMixer() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
}
