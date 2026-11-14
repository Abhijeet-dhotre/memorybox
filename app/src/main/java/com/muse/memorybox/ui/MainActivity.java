package com.muse.memorybox.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.muse.memorybox.R;
import com.muse.memorybox.data.AppSettings;
import com.muse.memorybox.data.MemoryDb;
import com.muse.memorybox.model.MemoryVideo;
import com.muse.memorybox.model.SearchHit;
import com.muse.memorybox.model.VideoCategories;
import com.muse.memorybox.services.IngestionService;
import com.muse.memorybox.services.SimpleSearch;
import com.muse.memorybox.util.AppLog;
import com.muse.memorybox.util.UrlExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home (PRD §9): large search bar, dynamic category chips, memory cards.
 * Saving happens through the Share sheet or the paste box (FR-1).
 */
public final class MainActivity extends AppCompatActivity {

    private MemoryDb db;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EditText searchInput;
    private EditText urlInput;
    private TextView statusText;
    private TextView emptyText;
    private Button clearSearchButton;
    private TextView logText;
    private ScrollView logScroll;
    private final Runnable logListener = () -> refreshLog();
    private LinearLayout chipsRow;
    private Spinner platformSpinner;
    private Spinner channelSpinner;
    private MemoryAdapter adapter;

    private String selectedCategory; // null = All
    private boolean searching;

    // Filter state actually applied to the list. Spinner events that do
    // not change these values are ignored — rebuilding the spinners on
    // every refresh used to refire selection events endlessly, which
    // kept rebinding the list (eating taps) and made filters feel laggy.
    private String appliedPlatform; // null = All
    private String appliedChannel;  // null = All
    private List<String> lastPlatforms;
    private List<String> lastChannels;
    private final List<TextView> chipViews = new ArrayList<>();
    private String lastShownKey;

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(IngestionService.EXTRA_STATUS);
            String message = intent.getStringExtra(IngestionService.EXTRA_MESSAGE);
            String title = intent.getStringExtra(IngestionService.EXTRA_TITLE);
            String text = title != null ? title + " — " + message : message;
            setStatus(text);
            Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (com.muse.memorybox.util.CrashReporter.showPendingTrace(this)) return;
        try {
            initUi();
        } catch (Throwable t) {
            com.muse.memorybox.util.CrashReporter.saveTrace(this, t);
            com.muse.memorybox.util.CrashReporter.showTraceScreen(this,
                    com.muse.memorybox.util.CrashReporter.traceToString(t));
        }
    }

    private void initUi() {
        setContentView(R.layout.activity_main);
        db = new MemoryDb(this);

        searchInput = findViewById(R.id.searchInput);
        urlInput = findViewById(R.id.urlInput);
        statusText = findViewById(R.id.statusText);
        emptyText = findViewById(R.id.emptyText);
        clearSearchButton = findViewById(R.id.clearSearchButton);
        chipsRow = findViewById(R.id.chipsRow);
        platformSpinner = findViewById(R.id.platformSpinner);
        channelSpinner = findViewById(R.id.channelSpinner);

        RecyclerView list = findViewById(R.id.memoryList);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MemoryAdapter();
        adapter.setOnItemClick((video, timestamp, thumbView) -> {
            Intent open = new Intent(this, DetailActivity.class);
            open.putExtra(DetailActivity.EXTRA_VIDEO_ID, video.id);
            open.putExtra(DetailActivity.EXTRA_TIMESTAMP, timestamp);
            // Shared-element expansion of the thumbnail into the detail
            // screen (App Store style); falls back to a plain open.
            try {
                androidx.core.app.ActivityOptionsCompat options =
                        androidx.core.app.ActivityOptionsCompat
                                .makeSceneTransitionAnimation(this, thumbView,
                                        "shared_thumbnail");
                startActivity(open, options.toBundle());
            } catch (Throwable t) {
                startActivity(open);
            }
        });
        list.setAdapter(adapter);

        logText = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll);
        findViewById(R.id.logClearButton).setOnClickListener(v -> AppLog.clear());
        AppLog.addListener(logListener);
        refreshLog();

        findViewById(R.id.settingsButton).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        findViewById(R.id.searchButton).setOnClickListener(v -> runSearch());
        searchInput.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                runSearch();
                return true;
            }
            return false;
        });
        clearSearchButton.setOnClickListener(v -> {
            searchInput.setText("");
            searching = false;
            clearSearchButton.setVisibility(View.GONE);
            refresh();
        });

        findViewById(R.id.saveButton).setOnClickListener(v -> {
            String url = UrlExtractor.extract(urlInput.getText().toString());
            if (url == null) {
                Toast.makeText(this, "Paste a video link first.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            urlInput.setText("");
            startSave(url);
        });

        buildChips();

        AdapterView.OnItemSelectedListener filterChanged = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                // Programmatic adapter resets refire this with the SAME
                // value — only a real change refreshes the list.
                String value = spinnerValue((Spinner) parent);
                if (parent.getId() == R.id.platformSpinner) {
                    if (java.util.Objects.equals(value, appliedPlatform)) return;
                    appliedPlatform = value;
                } else {
                    if (java.util.Objects.equals(value, appliedChannel)) return;
                    appliedChannel = value;
                }
                if (!searching) refresh();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        platformSpinner.setOnItemSelectedListener(filterChanged);
        channelSpinner.setOnItemSelectedListener(filterChanged);


        if (AppSettings.getGroqKey(this).isEmpty()) {
            setStatus("Tip: add your Groq API key in Settings to save new videos.");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ActivityCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, resultReceiver,
                new IntentFilter(IngestionService.ACTION_RESULT),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(resultReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        AppLog.removeListener(logListener);
        super.onDestroy();
        io.shutdownNow();
        if (db != null) db.close();
    }

    private void startSave(String url) {
        // Multiple saves are welcome: the service runs jobs in parallel
        // and queues the rest — a second save is never dropped.
        setStatus("Saving… you can save more videos meanwhile — they run together.");
        IngestionService.start(this, url);
    }

    private void setStatus(String text) {
        if (text == null || text.isEmpty()) {
            statusText.setVisibility(View.GONE);
            return;
        }
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE);
    }

    /** Reloads the memory list; spinners/chips update only on real change. */
    private void refresh() {
        io.execute(() -> {
            List<MemoryVideo> all = db.getAllVideos();
            List<String> platforms = db.platforms();
            List<String> channels = db.channels();
            List<MemoryVideo> shown = new ArrayList<>();
            for (MemoryVideo v : all) {
                if (passesFilters(v)) shown.add(v);
            }
            mainHandler.post(() -> {
                if (!platforms.equals(lastPlatforms) || !channels.equals(lastChannels)) {
                    lastPlatforms = platforms;
                    lastChannels = channels;
                    rebuildSpinners(platforms, channels);
                }
                updateChipStyles();
                if (!searching) {
                    String key = shownKey(shown);
                    if (!key.equals(lastShownKey)) {
                        lastShownKey = key;
                        adapter.showMemories(shown);
                    }
                    emptyText.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
                    emptyText.setText(shown.isEmpty()
                            ? "No memories here yet.\nShare a video to Memory Box, or paste a link above."
                            : "");
                }
            });
        });
    }

    /** Category (fixed list, with legacy-name mapping) + platform + channel. */
    private boolean passesFilters(MemoryVideo v) {
        if (appliedPlatform != null && !appliedPlatform.equals(v.platform)) return false;
        if (appliedChannel != null && !appliedChannel.equals(v.channel)) return false;
        return VideoCategories.belongsTo(v.category, selectedCategory);
    }

    private String shownKey(List<MemoryVideo> shown) {
        StringBuilder sb = new StringBuilder();
        sb.append(selectedCategory).append('|')
                .append(appliedPlatform).append('|').append(appliedChannel);
        for (MemoryVideo v : shown) sb.append('|').append(v.id);
        return sb.toString();
    }

    private String spinnerValue(Spinner spinner) {
        Object item = spinner.getSelectedItem();
        if (item == null) return null;
        String s = item.toString();
        return s.startsWith("All") ? null : s;
    }

    /** Chips are built ONCE (the fixed category list never changes);
     *  selecting one only restyles the chips and reloads the list. */
    private void buildChips() {
        chipsRow.removeAllViews();
        chipViews.clear();
        addChip("All");
        for (String category : VideoCategories.ALL) {
            addChip(category);
        }
        updateChipStyles();
    }

    private void addChip(String label) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(13);
        chip.setPadding(30, 13, 30, 13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(12);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            selectedCategory = "All".equals(label) ? null : label;
            searching = false;
            searchInput.setText("");
            clearSearchButton.setVisibility(View.GONE);
            updateChipStyles();
            refresh();
        });
        chipViews.add(chip);
        chipsRow.addView(chip);
    }

    private void updateChipStyles() {
        for (TextView chip : chipViews) {
            String label = chip.getText().toString();
            boolean selected = "All".equals(label)
                    ? selectedCategory == null
                    : label.equals(selectedCategory);
            chip.setBackgroundResource(selected
                    ? R.drawable.bg_ios_chip_selected : R.drawable.bg_ios_chip);
            chip.setTextColor(getColor(selected
                    ? android.R.color.white : R.color.mb_text));
            chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void rebuildSpinners(List<String> platforms, List<String> channels) {
        // Restore the APPLIED filter values; the refired selection
        // events carry the same values and are ignored by the listener.
        setSpinner(platformSpinner, "All platforms", platforms, appliedPlatform);
        setSpinner(channelSpinner, "All channels", channels, appliedChannel);
    }

    private void setSpinner(Spinner spinner, String allLabel,
                            List<String> values, String previous) {
        List<String> items = new ArrayList<>();
        items.add(allLabel);
        items.addAll(values);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, items);
        spinner.setAdapter(adapter);
        if (previous != null) {
            int idx = items.indexOf(previous);
            if (idx >= 0) spinner.setSelection(idx);
        }
    }

    /** Plain text search: query words matched against title, tags,
     *  category, summary, key points and transcript text. Fast — no
     *  embeddings, no ranking model. Active filters still apply. */
    private void runSearch() {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            searching = false;
            clearSearchButton.setVisibility(View.GONE);
            refresh();
            return;
        }
        searching = true;
        clearSearchButton.setVisibility(View.VISIBLE);
        setStatus("Searching…");
        io.execute(() -> {
            List<MemoryVideo> filtered = new ArrayList<>();
            for (MemoryVideo v : db.getAllVideos()) {
                if (passesFilters(v)) filtered.add(v);
            }
            List<SearchHit> hits = SimpleSearch.search(
                    query, filtered, db.getAllTranscriptTextsByVideo());
            mainHandler.post(() -> {
                adapter.showResults(hits);
                emptyText.setVisibility(hits.isEmpty() ? View.VISIBLE : View.GONE);
                emptyText.setText(hits.isEmpty()
                        ? "No matches. Try different or fewer words."
                        : "");
                setStatus(hits.size() + " result" + (hits.size() == 1 ? "" : "s")
                        + " for “" + query + "”");
                AppLog.log("Search: “" + query + "” — " + hits.size() + " result(s)");
            });
        });
    }

    private void refreshLog() {
        if (logText == null) return;
        logText.setText(AppLog.getAll());
        if (logScroll != null) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

}
