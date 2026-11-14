package com.muse.memorybox.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.muse.memorybox.R;
import com.muse.memorybox.data.AppSettings;
import com.muse.memorybox.data.MemoryDb;
import com.muse.memorybox.services.CategoryResolver;
import com.muse.memorybox.services.GroqClient;
import com.muse.memorybox.services.YtDlp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings: the user's Groq key (on-device only, PRD §11), library info,
 * yt-dlp update, library export/import (the one .db file, §11.2), and
 * dynamic-category rename/merge/delete (§7.3).
 */
public final class SettingsActivity extends AppCompatActivity {

    private MemoryDb db;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Spinner categorySpinner;

    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/octet-stream"),
            uri -> {
                if (uri != null) exportTo(uri);
            });

    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) importFrom(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        db = new MemoryDb(this);
        categorySpinner = findViewById(R.id.categorySpinner);

        EditText keyInput = findViewById(R.id.groqKeyInput);
        keyInput.setText(AppSettings.getGroqKey(this));
        findViewById(R.id.saveKeyButton).setOnClickListener(v -> {
            AppSettings.setGroqKey(this, keyInput.getText().toString());
            Toast.makeText(this, "Groq key saved on this device.",
                    Toast.LENGTH_SHORT).show();
        });

        ((TextView) findViewById(R.id.modelsInfo)).setText(
                "Transcription: Groq " + GroqClient.TRANSCRIPTION_MODEL
                        + " · Memory creation: Groq " + GroqClient.CHAT_MODEL
                        + "\nSearch: hybrid keyword + meaning, ranked by the PRD §7.1 "
                        + "rule. Embeddings run on-device (384-dim); meaning search "
                        + "works offline.");

        findViewById(R.id.exportButton).setOnClickListener(v ->
                exportLauncher.launch(MemoryDb.DB_NAME));
        findViewById(R.id.importButton).setOnClickListener(v ->
                importLauncher.launch(new String[]{"application/octet-stream", "*/*"}));

        findViewById(R.id.renameCategoryButton).setOnClickListener(v -> renameCategory());
        findViewById(R.id.mergeCategoryButton).setOnClickListener(v -> mergeCategory());
        findViewById(R.id.deleteCategoryButton).setOnClickListener(v -> deleteCategory());

        refreshInfo();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        if (db != null) db.close();
    }

    private void refreshInfo() {
        io.execute(() -> {
            int count = db.countVideos();
            long bytes = db.databaseSizeBytes(this);
            List<String> categories = db.categories();
            mainHandler.post(() -> {
                ((TextView) findViewById(R.id.libraryInfo)).setText(String.format(
                        Locale.ROOT,
                        "Library: %d memories · %.1f KB total (budget ≈ 30–50 KB "
                                + "per video including vectors and the keyword index).",
                        count, bytes / 1024.0));
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_dropdown_item,
                        categories.isEmpty()
                                ? new ArrayList<>(java.util.Collections.singletonList("—"))
                                : categories);
                categorySpinner.setAdapter(adapter);
            });
        });
    }

    private String selectedCategory() {
        Object item = categorySpinner.getSelectedItem();
        return item == null ? null : item.toString();
    }

    private void renameCategory() {
        String from = selectedCategory();
        if (from == null || from.equals("—")) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(from);
        new AlertDialog.Builder(this)
                .setTitle("Rename category")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Rename", (d, w) -> {
                    // Categories are a fixed list only: whatever name is
                    // typed is mapped onto the closest fixed category.
                    String to = com.muse.memorybox.model.VideoCategories
                            .matchOrFallback(input.getText().toString());
                    io.execute(() -> {
                        db.renameCategory(from, to);
                        mainHandler.post(() -> {
                            Toast.makeText(this, "Renamed to " + to,
                                    Toast.LENGTH_SHORT).show();
                            refreshInfo();
                        });
                    });
                })
                .show();
    }

    private void mergeCategory() {
        String from = selectedCategory();
        if (from == null || from.equals("—")) return;
        io.execute(() -> {
            List<String> targets = new ArrayList<>();
            for (String c : db.categories()) if (!c.equals(from)) targets.add(c);
            mainHandler.post(() -> {
                if (targets.isEmpty()) {
                    Toast.makeText(this, "No other category to merge into.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Merge “" + from + "” into…")
                        .setItems(targets.toArray(new String[0]), (d, which) -> {
                            String into = targets.get(which);
                            io.execute(() -> {
                                db.mergeCategories(from, into);
                                mainHandler.post(() -> {
                                    Toast.makeText(this,
                                            "Merged into " + into,
                                            Toast.LENGTH_SHORT).show();
                                    refreshInfo();
                                });
                            });
                        })
                        .show();
            });
        });
    }

    private void deleteCategory() {
        String from = selectedCategory();
        if (from == null || from.equals("—")) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete category “" + from + "”?")
                .setMessage("Its videos are kept and become Uncategorized.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> io.execute(() -> {
                    db.deleteCategory(from);
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Category deleted.",
                                Toast.LENGTH_SHORT).show();
                        refreshInfo();
                    });
                }))
                .show();
    }

    // ------------------------------------------------------------------
    // Export / import of the single library file (§11.2)
    // ------------------------------------------------------------------

    private void exportTo(Uri uri) {
        io.execute(() -> {
            String message;
            try {
                db.close();
                File dbFile = getDatabasePath(MemoryDb.DB_NAME);
                try (InputStream in = new FileInputStream(dbFile);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("No output stream");
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                message = "Library exported.";
            } catch (Exception e) {
                message = "Export failed: " + e.getMessage();
            }
            String result = message;
            mainHandler.post(() -> {
                db = new MemoryDb(this);
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                refreshInfo();
            });
        });
    }

    private void importFrom(Uri uri) {
        io.execute(() -> {
            String message;
            try {
                db.close();
                File dbFile = getDatabasePath(MemoryDb.DB_NAME);
                File parent = dbFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(dbFile)) {
                    if (in == null) throw new IllegalStateException("No input stream");
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                // Remove stale journal files so the imported db opens cleanly.
                new File(dbFile.getPath() + "-wal").delete();
                new File(dbFile.getPath() + "-shm").delete();
                new File(dbFile.getPath() + "-journal").delete();
                message = "Library imported. Restart Memory Box to see it.";
            } catch (Exception e) {
                message = "Import failed: " + e.getMessage();
            }
            String result = message;
            mainHandler.post(() -> {
                db = new MemoryDb(this);
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                refreshInfo();
            });
        });
    }
}
