package com.mtsguerra.guessthesong.engine;

import com.mtsguerra.guessthesong.model.RoundResult;
import com.mtsguerra.guessthesong.model.Track;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scores a round (max 5 points):
 * <ul>
 *   <li>Title correct → +2</li>
 *   <li>Main artist correct → +2</li>
 *   <li>Featured artist(s) correct → +1 (auto-awarded when the track has no features)</li>
 * </ul>
 * A guess matches when, after {@link TextNormalizer#compact}, it is identical, within the
 * allowed edit distance, or at least {@code minSimilarity} similar.
 */
public final class ScoringEngine {

    public static final int TITLE_POINTS = 2;
    public static final int MAIN_ARTIST_POINTS = 2;
    public static final int FEATURED_POINTS = 1;
    public static final int MAX_POINTS_PER_ROUND = TITLE_POINTS + MAIN_ARTIST_POINTS + FEATURED_POINTS;

    private static final Pattern BRACKETED = Pattern.compile("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]");
    private static final Pattern DASH_SUFFIX = Pattern.compile("\\s+[-–—]\\s+.*$");
    private static final Pattern LIST_SEPARATORS = Pattern.compile("\\s*[,;/+]\\s*");
    private static final Pattern WORD_SEPARATORS = Pattern.compile(
            "\\s*(?:&|\\bx\\b|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\bwith\\b|\\band\\b)\\s*",
            Pattern.CASE_INSENSITIVE);

    private final int maxEditDistance;
    private final double minSimilarity;

    public ScoringEngine() {
        this(2, 0.85);
    }

    public ScoringEngine(int maxEditDistance, double minSimilarity) {
        this.maxEditDistance = maxEditDistance;
        this.minSimilarity = minSimilarity;
    }

    public RoundResult score(int round, Track track, String titleGuess, String mainArtistGuess, String featuredGuess) {
        boolean title = titleMatches(titleGuess, track);
        boolean main = isMatch(mainArtistGuess, track.mainArtist());
        boolean featured = featuredMatches(featuredGuess, track);

        int points = (title ? TITLE_POINTS : 0)
                + (main ? MAIN_ARTIST_POINTS : 0)
                + (featured ? FEATURED_POINTS : 0);

        return new RoundResult(round, track,
                nullToEmpty(titleGuess), nullToEmpty(mainArtistGuess), nullToEmpty(featuredGuess),
                title, main, featured, points);
    }

    /** Fuzzy comparison of a single guess against a single expected answer. */
    public boolean isMatch(String guess, String answer) {
        String g = TextNormalizer.compact(guess);
        String a = TextNormalizer.compact(answer);
        if (g.isEmpty() || a.isEmpty()) {
            return false;
        }
        if (g.equals(a)) {
            return true;
        }
        int distance = Levenshtein.distance(g, a);
        if (distance <= allowedDistance(a.length())) {
            return true;
        }
        return Levenshtein.similarity(g, a) >= minSimilarity;
    }

    /**
     * Accepts the cleaned title, or the title with any leftover bracketed / dashed part removed
     * (e.g. "Bad Guy" for "bad guy (with Justin Bieber)").
     */
    public boolean titleMatches(String guess, Track track) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(track.title());
        candidates.add(BRACKETED.matcher(track.title()).replaceAll("").trim());
        candidates.add(DASH_SUFFIX.matcher(track.title()).replaceAll("").trim());
        return candidates.stream()
                .filter(c -> !c.isBlank())
                .anyMatch(c -> isMatch(guess, c));
    }

    /**
     * No features → always true (+1 by default).
     * Otherwise every featured artist must be named, in any order, separated by
     * commas, "&", "and", "x", "feat.", etc. Extra names are not penalised.
     */
    public boolean featuredMatches(String guess, Track track) {
        if (!track.hasFeatures()) {
            return true;
        }
        if (guess == null || guess.isBlank()) {
            return false;
        }
        List<String> segments = splitArtists(guess);
        String compactGuess = TextNormalizer.compact(guess);

        for (String artist : track.featuredArtists()) {
            String compactArtist = TextNormalizer.compact(artist);
            boolean containedVerbatim = compactArtist.length() >= 3 && compactGuess.contains(compactArtist);
            boolean fuzzySegment = segments.stream().anyMatch(s -> isMatch(s, artist));
            if (!containedVerbatim && !fuzzySegment) {
                return false;
            }
        }
        return true;
    }

    /**
     * Splits "Drake, Florence and the Machine & Future" into candidate names at several
     * granularities, so names that contain "and" still survive as a whole segment.
     */
    static List<String> splitArtists(String guess) {
        Set<String> segments = new LinkedHashSet<>();
        segments.add(guess.trim());
        for (String coarse : LIST_SEPARATORS.split(guess)) {
            segments.add(coarse.trim());
            for (String fine : WORD_SEPARATORS.split(coarse)) {
                segments.add(fine.trim());
            }
        }
        segments.removeIf(String::isBlank);
        return List.copyOf(segments);
    }

    /** Short answers get less slack, so "Up" can't be matched by "No". */
    private int allowedDistance(int answerLength) {
        if (answerLength <= 3) {
            return 0;
        }
        if (answerLength <= 6) {
            return Math.min(1, maxEditDistance);
        }
        return maxEditDistance;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
