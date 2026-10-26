package app.wn;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import java.util.List;

/** Saved profiles outlive generators. Removing a generator never deletes its profile. */
public final class ProfilesActivity extends ProfileExchangeActivity {
    private LinearLayout list;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = Ui.screen(this, "Profiles", true);
        Button mic = Ui.button(this, "Profile from microphone", false), audio = Ui.button(this, "Profile from audio file", false);
        Button imported = Ui.button(this, "Import JSON", false);
        mic.setOnClickListener(v -> startActivity(new Intent(this, ProfilingActivity.class).putExtra("source", "mic")));
        audio.setOnClickListener(v -> startActivity(new Intent(this, ProfilingActivity.class).putExtra("source", "file")));
        imported.setOnClickListener(v -> importJson());
        Ui.add(this, body, mic, 14); Ui.add(this, body, audio, 8); Ui.add(this, body, imported, 8);
        list = Ui.column(this); body.addView(list);
    }
    @Override protected void onResume() { super.onResume(); render(); }
    @Override protected void onProfileImported(String id) { render(); }
    private void render() {
        list.removeAllViews();
        List<SettingsStore.SavedProfile> profiles = settings.profiles();
        if (profiles.isEmpty()) { Ui.add(this, list, Ui.text(this, "No saved profiles.", 14, Ui.MUTED, false), 20); return; }
        for (SettingsStore.SavedProfile saved : profiles) {
            LinearLayout card = Ui.card(this, list), header = Ui.row(this);
            header.addView(Ui.text(this, saved.preset.name, 17, Ui.INK, true), new LinearLayout.LayoutParams(0, -2, 1));
            Button more = Ui.button(this, "⋮", false); more.setContentDescription("Actions for " + saved.preset.name);
            header.addView(more, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48))); card.addView(header);
            String detail = saved.preset.isOctaveProfile() ? "10-band profile" : saved.preset.frequencyBands().length + " frequency bands";
            Ui.add(this, card, Ui.text(this, detail, 12, Ui.MUTED, false), 6);
            Button add = Ui.button(this, "Add generator", false);
            add.setOnClickListener(v -> { try { settings.addGenerator(saved.id); openMixer(); } catch (RuntimeException e) { Ui.error(this, "Generator", e.getMessage()); } });
            Button export = Ui.button(this, "Export JSON", false); export.setOnClickListener(v -> exportJson(saved.preset));
            LinearLayout actions = Ui.row(this);
            actions.addView(add, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
            LinearLayout.LayoutParams exportLayout = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1); exportLayout.leftMargin = Ui.dp(this, 8);
            actions.addView(export, exportLayout); Ui.add(this, card, actions, 12);
            more.setOnClickListener(v -> {
                PopupMenu menu = new PopupMenu(this, more);
                menu.getMenu().add("Rename"); menu.getMenu().add("Delete");
                menu.setOnMenuItemClickListener(item -> {
                    String action = item.getTitle().toString();
                    if ("Export JSON".equals(action)) exportJson(saved.preset);
                    else if ("Rename".equals(action)) rename(saved);
                    else new AlertDialog.Builder(this).setTitle("Delete profile?")
                            .setMessage(saved.preset.name).setPositiveButton("Delete", (dialog, which) -> {
                                try { settings.deleteProfile(saved.id); render(); } catch (RuntimeException e) { Ui.error(this, "Delete profile", e.getMessage()); }
                            }).setNegativeButton("Cancel", null).show();
                    return true;
                }); menu.show();
            });
        }
    }
    private void rename(SettingsStore.SavedProfile saved) {
        EditText name = new EditText(this); name.setText(saved.preset.name); name.setSingleLine(true); name.setSelectAllOnFocus(true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Profile name").setView(name)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try { settings.renameProfile(saved.id, name.getText().toString()); render(); dialog.dismiss(); }
            catch (RuntimeException e) { name.setError(e.getMessage()); }
        })); dialog.show();
    }
}
