package com.muse.memorybox.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.muse.memorybox.services.IngestionService;
import com.muse.memorybox.util.UrlExtractor;

/**
 * Share-sheet entry point (FR-1): sharing a link from YouTube, Instagram,
 * X, TikTok or a browser starts the save in the background — this
 * activity shows no UI of its own.
 */
public final class ShareReceiverActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String text = null;
        Intent intent = getIntent();
        if (intent != null) {
            if (Intent.ACTION_SEND.equals(intent.getAction())) {
                text = intent.getStringExtra(Intent.EXTRA_TEXT);
                if (text == null) text = intent.getStringExtra(Intent.EXTRA_SUBJECT);
            } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                Uri data = intent.getData();
                if (data != null) text = data.toString();
            }
        }
        String url = UrlExtractor.extract(text);
        if (url != null) {
            IngestionService.start(this, url);
            Toast.makeText(this, "Saving to Memory Box…", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "No link found in that share.", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
