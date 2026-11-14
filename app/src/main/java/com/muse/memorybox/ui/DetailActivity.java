package com.muse.memorybox.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.muse.memorybox.R;
import com.muse.memorybox.data.MemoryDb;
import com.muse.memorybox.model.Chunk;
import com.muse.memorybox.model.MemoryVideo;
import com.muse.memorybox.services.SimpleSearch;
import com.muse.memorybox.util.Texts;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Memory detail (PRD §9): summary, key points, full timestamped
 * transcript, search inside this video, open-original at a timestamp
 * (FR-11), delete (FR-12).
 */
public final class DetailActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_ID = "com.muse.memorybox.extra.VIDEO_ID";
    public static final String EXTRA_TIMESTAMP = "com.muse.memorybox.extra.TIMESTAMP";

    private MemoryDb db;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MemoryVideo video;
    private List<Chunk> chunks = new ArrayList<>();
    private int focusTimestamp;

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
        setContentView(R.layout.activity_detail);
        db = new MemoryDb(this);
        long videoId = getIntent().getLongExtra(EXTRA_VIDEO_ID, -1);
        focusTimestamp = getIntent().getIntExtra(EXTRA_TIMESTAMP, 0);
        if (videoId < 0) {
            finish();
            return;
        }
        io.execute(() -> {
            try {
                MemoryVideo loaded = db.getVideo(videoId);
                List<Chunk> loadedChunks = loaded == null
                        ? new ArrayList<>() : db.getChunks(videoId);
                mainHandler.post(() -> {
                    if (loaded == null) {
                        Toast.makeText(this, "This memory no longer exists.",
                                Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }
                    video = loaded;
                    chunks = loadedChunks;
                    try {
                        bind();
                    } catch (Throwable t) {
                        com.muse.memorybox.util.CrashReporter.saveTrace(this, t);
                        com.muse.memorybox.util.CrashReporter.showTraceScreen(this,
                                com.muse.memorybox.util.CrashReporter.traceToString(t));
                    }
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    com.muse.memorybox.util.CrashReporter.saveTrace(this, t);
                    com.muse.memorybox.util.CrashReporter.showTraceScreen(this,
                            com.muse.memorybox.util.CrashReporter.traceToString(t));
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        if (db != null) db.close();
    }

    private void bind() {
        ThumbnailLoader.load(findViewById(R.id.detailThumb), video.thumbnailUrl);
        TextView title = findViewById(R.id.detailTitle);
        title.setText(video.title);
        // "What is in this video" section: title first, then the summary.
        ((TextView) findViewById(R.id.detailSectionTitle)).setText(video.title);

        StringBuilder meta = new StringBuilder();
        meta.append(video.platform);
        if (!video.channel.isEmpty()) meta.append(" · ").append(video.channel);
        meta.append(" · saved ").append(video.dateSaved);
        meta.append(" · ").append(video.category);
        if (video.durationSeconds > 0) {
            meta.append(" · ").append(Texts.formatTimestamp(video.durationSeconds));
        }
        if (!video.language.isEmpty()) meta.append(" · ").append(video.language);
        ((TextView) findViewById(R.id.detailMeta)).setText(meta.toString());

        ((TextView) findViewById(R.id.detailSummary)).setText(video.summary);

        StringBuilder kp = new StringBuilder();
        for (String point : video.keyPoints) {
            if (kp.length() > 0) kp.append('\n');
            kp.append("• ").append(point);
        }
        ((TextView) findViewById(R.id.detailKeyPoints))
                .setText(kp.length() == 0 ? "—" : kp.toString());

        ((TextView) findViewById(R.id.detailTags)).setText(
                video.tags.isEmpty() ? "" : "Tags: " + Texts.join(", ", video.tags));

        TextView open = findViewById(R.id.openButton);
        if (focusTimestamp > 0) {
            open.setText("Open at " + Texts.formatTimestamp(focusTimestamp));
        }
        open.setOnClickListener(v -> openOriginal(focusTimestamp));
        findViewById(R.id.deleteButton).setOnClickListener(v -> confirmDelete());

        EditText inVideoInput = findViewById(R.id.inVideoSearchInput);
        findViewById(R.id.inVideoSearchButton).setOnClickListener(v -> {
            String q = inVideoInput.getText().toString().trim();
            if (!q.isEmpty()) searchWithin(q);
        });

        LinearLayout transcript = findViewById(R.id.transcriptContainer);
        transcript.removeAllViews();
        List<Chunk> transcriptChunks = new ArrayList<>();
        for (Chunk c : chunks) if (c.isTranscript()) transcriptChunks.add(c);
        transcriptChunks.sort((a, b) -> Integer.compare(a.startSeconds, b.startSeconds));
        for (Chunk c : transcriptChunks) {
            TextView row = new TextView(this);
            row.setText("[" + Texts.formatTimestamp(c.startSeconds) + "]  " + c.text);
            row.setTextSize(13);
            row.setPadding(0, 10, 0, 10);
            row.setOnClickListener(v -> openOriginal(c.startSeconds));
            transcript.addView(row);
        }
        if (transcriptChunks.isEmpty()) {
            TextView row = new TextView(this);
            row.setText("No transcript chunks.");
            transcript.addView(row);
        }
    }

    /** FR-11: open the original on its platform, at the timestamp. */
    private void openOriginal(int timestampSeconds) {
        String url = video.originalUrl;
        if (timestampSeconds > 0 && isYouTube(url)) {
            url = url + (url.contains("?") ? "&" : "?") + "t=" + timestampSeconds + "s";
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "No app can open this link.", Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean isYouTube(String url) {
        String lower = url.toLowerCase();
        return lower.contains("youtube.com") || lower.contains("youtu.be");
    }

    /** Plain text scan of this video's transcript — no embeddings. */
    private void searchWithin(String query) {
        LinearLayout container = findViewById(R.id.hitsContainer);
        container.removeAllViews();
        io.execute(() -> {
            List<String> tokens = SimpleSearch.tokens(query);
            String phrase = query.trim().toLowerCase(java.util.Locale.ROOT);
            List<Moment> hits = new ArrayList<>();
            List<Chunk> sorted = new ArrayList<>();
            for (Chunk c : chunks) if (c.isTranscript()) sorted.add(c);
            sorted.sort((a, b) -> Integer.compare(a.startSeconds, b.startSeconds));
            for (Chunk c : sorted) {
                String text = c.text.toLowerCase(java.util.Locale.ROOT);
                boolean hit = !phrase.isEmpty() && text.contains(phrase);
                if (!hit) {
                    for (String t : tokens) {
                        if (text.contains(t)) { hit = true; break; }
                    }
                }
                if (hit) {
                    hits.add(new Moment(c.startSeconds,
                            SimpleSearch.snippetAround(c.text, tokens, phrase)));
                    if (hits.size() >= 50) break;
                }
            }
            mainHandler.post(() -> {
                if (hits.isEmpty()) {
                    TextView none = new TextView(this);
                    none.setText("No matching moment in this video.");
                    none.setPadding(0, 8, 0, 8);
                    container.addView(none);
                    return;
                }
                for (Moment hit : hits) {
                    TextView row = new TextView(this);
                    row.setText("[" + Texts.formatTimestamp(hit.timestampSeconds)
                            + "]  " + hit.sentence);
                    row.setTextSize(13);
                    row.setPadding(0, 10, 0, 10);
                    row.setOnClickListener(v -> openOriginal(hit.timestampSeconds));
                    container.addView(row);
                }
            });
        });
    }

    private static final class Moment {
        final int timestampSeconds;
        final String sentence;

        Moment(int timestampSeconds, String sentence) {
            this.timestampSeconds = timestampSeconds;
            this.sentence = sentence;
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Delete this memory?")
                .setMessage("Its transcript, summary and search entries are removed "
                        + "completely. The original video is untouched.")
                .setNegativeButton("Keep", null)
                .setPositiveButton("Delete", (dialog, which) -> io.execute(() -> {
                    db.deleteVideo(video.id);
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Memory deleted.", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }))
                .show();
    }
}
