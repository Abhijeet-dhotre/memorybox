package com.muse.memorybox.services;

import com.muse.memorybox.model.VideoCategories;

import java.util.List;

/**
 * Category assignment: the app has ONE fixed list of video categories
 * (chosen by the user) and nothing else. Whatever the model suggests is
 * mapped onto the closest fixed category; no new category is ever
 * created.
 */
public final class CategoryResolver {

    private CategoryResolver() {
    }

    /**
     * Maps a suggested category onto the fixed list (exact, alias or
     * closest token match), falling back to
     * {@link VideoCategories#FALLBACK}. The {@code existing} parameter is
     * kept for call-site compatibility and is intentionally unused —
     * existing free-form categories no longer extend the list.
     */
    public static String resolveCategory(String suggested, List<String> existing) {
        return VideoCategories.matchOrFallback(suggested);
    }
}
