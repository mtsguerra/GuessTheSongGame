package com.mtsguerra.guessthesong.spotify;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes release noise from Spotify track names and pulls out featured artists.
 * <pre>
 * "Bohemian Rhapsody - Remastered 2011"           → "Bohemian Rhapsody"
 * "Señorita (feat. Camila Cabello)"               → "Señorita"      + [Camila Cabello]
 * "Old Town Road feat. Billy Ray Cyrus - Remix"   → "Old Town Road" + [Billy Ray Cyrus]
 * "Let It Go - From \"Frozen\"/Soundtrack Version" → "Let It Go"
 * </pre>
 */
public final class TitleCleaner {

    public record CleanTitle(String title, List<String> featuredArtists) {
        public CleanTitle {
            featuredArtists = List.copyOf(featuredArtists);
        }
    }

    private static final Pattern BRACKETED_FEAT = Pattern.compile(
            "\\s*[\\(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|with)\\s+([^\\)\\]]+)[\\)\\]]",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TRAILING_FEAT = Pattern.compile(
            "\\s+(?:feat\\.?|ft\\.|featuring)\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);

    private static final String NOISE_WORDS = String.join("|",
            "remaster(?:ed)?", "re-?master(?:ed)?", "deluxe", "edition", "bonus", "anniversary",
            "version", "mono", "stereo", "edit", "live", "demo", "explicit", "remix", "mix",
            "soundtrack", "from", "expanded", "re-?recorded", "instrumental", "acoustic",
            "unplugged", "reissue", "bootleg", "extended", "taylor'?s");

    private static final Pattern NOISE = Pattern.compile(
            "\\b(?:" + NOISE_WORDS + ")\\b|\\b(?:19|20)\\d{2}\\b",
            Pattern.CASE_INSENSITIVE);

    /** Innermost (...) or [...] group. */
    private static final Pattern BRACKET_GROUP = Pattern.compile("\\s*[\\(\\[]([^\\(\\)\\[\\]]*)[\\)\\]]");
    private static final Pattern DASH_SEPARATOR = Pattern.compile("\\s+[-–—]\\s+");
    private static final Pattern NAME_SEPARATORS = Pattern.compile("\\s*(?:,|&|\\band\\b|\\bx\\b)\\s*",
            Pattern.CASE_INSENSITIVE);

    private TitleCleaner() {
    }

    public static CleanTitle clean(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return new CleanTitle("", List.of());
        }
        String title = rawTitle.trim();
        Set<String> featured = new LinkedHashSet<>();

        // 1. "(feat. X)" / "[with Y]"
        Matcher m = BRACKETED_FEAT.matcher(title);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            featured.addAll(splitNames(m.group(1)));
            m.appendReplacement(sb, "");
        }
        m.appendTail(sb);
        title = sb.toString();

        // 2. " - Remastered 2011", " - Live at Wembley", " - From "Frozen""
        title = dropNoisyDashSegments(title);

        // 3. "Song feat. X" without brackets
        m = TRAILING_FEAT.matcher(title);
        if (m.find()) {
            featured.addAll(splitNames(m.group(1)));
            title = title.substring(0, m.start());
        }

        // 4. "(Deluxe Edition)", "[2009 Remaster]"
        title = dropNoisyBrackets(title);

        title = title.replaceAll("\\s{2,}", " ").replaceAll("[\\s\\-–—:]+$", "").trim();
        if (title.isEmpty()) {
            title = rawTitle.trim();
        }
        return new CleanTitle(title, new ArrayList<>(featured));
    }

    private static String dropNoisyDashSegments(String title) {
        String[] parts = DASH_SEPARATOR.split(title);
        StringBuilder kept = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (NOISE.matcher(parts[i]).find()) {
                break; // drop this segment and everything after it
            }
            kept.append(" - ").append(parts[i]);
        }
        return kept.toString();
    }

    private static String dropNoisyBrackets(String title) {
        String previous;
        do {
            previous = title;
            Matcher m = BRACKET_GROUP.matcher(title);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                boolean noisy = NOISE.matcher(m.group(1)).find();
                m.appendReplacement(sb, noisy ? "" : Matcher.quoteReplacement(m.group()));
            }
            m.appendTail(sb);
            title = sb.toString();
        } while (!title.equals(previous));
        return title;
    }

    static List<String> splitNames(String names) {
        List<String> out = new ArrayList<>();
        for (String n : NAME_SEPARATORS.split(names)) {
            String trimmed = n.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
