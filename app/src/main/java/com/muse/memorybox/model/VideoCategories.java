package com.muse.memorybox.model;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The app's ONLY video categories (fixed list chosen by the user,
 * 2026-11-14). New saves must land in exactly one of these; the home
 * filter chips show exactly these; older free-form category names are
 * mapped onto the closest entry so nothing disappears from filters.
 */
public final class VideoCategories {

    public static final List<String> ALL = Arrays.asList(
            "Entertainment",
            "Education",
            "Science & Technology",
            "Artificial Intelligence",
            "Computer Science",
            "Programming",
            "Tutorials / How-To",
            "Music",
            "Gaming",
            "Comedy",
            "Film & Animation",
            "Sports",
            "News & Politics",
            "Business & Finance",
            "Health & Fitness",
            "Food & Cooking",
            "Travel",
            "Lifestyle",
            "Vlogs",
            "Motivation",
            "History",
            "Nature & Animals");

    public static final String FALLBACK = "Education";

    /** Whole-phrase aliases, keyed by normalized text. */
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        alias("Artificial Intelligence", "ai", "artificial intelligence",
                "machine learning", "ml", "deep learning", "chatgpt", "llm",
                "generative ai", "neural networks");
        alias("Computer Science", "computer science", "cs", "computing",
                "data structures", "algorithms", "operating systems",
                "computer engineering");
        alias("Programming", "programming", "coding", "software development",
                "web development", "app development", "developer", "python",
                "java", "javascript");
        alias("Science & Technology", "science", "technology", "tech",
                "science and technology", "gadgets", "space", "physics",
                "electronics");
        alias("Tutorials / How-To", "tutorials", "tutorial", "how to",
                "how-to", "guide", "howto");
        alias("Education", "education", "educational", "study", "studies",
                "exam", "exams", "gate", "course", "lecture", "learning",
                "school", "college");
        alias("Film & Animation", "film", "films", "movie", "movies",
                "animation", "cinema", "anime");
        alias("Music", "music", "songs", "song", "album", "concert");
        alias("Gaming", "gaming", "games", "game", "esports", "gameplay");
        alias("Comedy", "comedy", "funny", "humor", "humour", "stand up",
                "standup");
        alias("Sports", "sports", "sport", "cricket", "football", "soccer",
                "fifa", "olympics");
        alias("News & Politics", "news", "politics", "political",
                "current affairs", "election", "elections");
        alias("Business & Finance", "business", "finance", "money",
                "stocks", "stock market", "investing", "investment",
                "startup", "startups", "marketing", "economics");
        alias("Health & Fitness", "health", "fitness", "workout", "gym",
                "exercise", "yoga", "medical", "medicine");
        alias("Food & Cooking", "food", "cooking", "recipes", "recipe",
                "cuisine", "chef");
        alias("Travel", "travel", "travelling", "traveling", "tourism",
                "trip", "trips");
        alias("Lifestyle", "lifestyle", "fashion", "beauty", "daily life",
                "home", "diy");
        alias("Vlogs", "vlogs", "vlog", "vlogging");
        alias("Motivation", "motivation", "motivational", "self improvement",
                "self help", "inspiration", "inspirational", "productivity");
        alias("History", "history", "historical", "ancient");
        alias("Nature & Animals", "nature", "animals", "animal", "wildlife",
                "pets", "dog", "dogs", "cat", "cats");
        alias("Entertainment", "entertainment", "entertaining", "celebrity",
                "celebrities", "bollywood", "hollywood");
    }

    private static void alias(String category, String... names) {
        for (String name : names) {
            ALIASES.put(normalize(name), category);
        }
    }

    private VideoCategories() {
    }

    /** Lowercase, & → and, punctuation stripped, single spaces. */
    public static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Maps any free-form category text to the closest fixed category:
     * exact match, then alias, then token overlap. Null when nothing
     * fits at all.
     */
    public static String match(String raw) {
        String n = normalize(raw);
        if (n.isEmpty()) return null;
        for (String c : ALL) {
            if (normalize(c).equals(n)) return c;
        }
        String aliased = ALIASES.get(n);
        if (aliased != null) return aliased;

        Set<String> tokens = tokenSet(n);
        String best = null;
        int bestOverlap = 0;
        for (String c : ALL) {
            Set<String> ct = tokenSet(normalize(c));
            int overlap = 0;
            for (String t : tokens) {
                if (ct.contains(t)) overlap++;
            }
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = c;
            }
        }
        return bestOverlap > 0 ? best : null;
    }

    /** match() with the app fallback, for categorizing new saves. */
    public static String matchOrFallback(String raw) {
        String m = match(raw);
        return m != null ? m : FALLBACK;
    }

    /** True when a stored (possibly legacy free-form) category falls
     *  under the given fixed filter category. */
    public static boolean belongsTo(String storedCategory, String filterCategory) {
        if (filterCategory == null) return true;
        if (storedCategory == null) return false;
        if (storedCategory.trim().equalsIgnoreCase(filterCategory.trim())) return true;
        return filterCategory.equals(match(storedCategory));
    }

    private static Set<String> tokenSet(String normalized) {
        Set<String> out = new HashSet<>();
        for (String t : normalized.split(" ")) {
            if (!t.isEmpty() && !t.equals("and")) out.add(t);
        }
        return out;
    }
}
