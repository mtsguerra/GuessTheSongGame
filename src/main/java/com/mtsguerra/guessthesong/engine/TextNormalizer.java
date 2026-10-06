package com.mtsguerra.guessthesong.engine;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns free-text guesses and answers into a canonical form so that
 * "Beyoncé", "beyonce" and "BEYONCE!" all compare equal.
 */
public final class TextNormalizer {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern APOSTROPHES = Pattern.compile("['’‘`´]");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern LEADING_THE = Pattern.compile("^the ");
    private static final Pattern AND_WORD = Pattern.compile("\\band\\b");

    /** Letters that NFD does not decompose into base + accent. */
    private static final Map<String, String> SPECIAL_LETTERS = Map.of(
            "ø", "o", "æ", "ae", "œ", "oe", "ß", "ss", "ł", "l",
            "đ", "d", "ð", "d", "þ", "th", "ı", "i");

    private TextNormalizer() {
    }

    /**
     * Lower-case, strip accents (NFD), drop apostrophes, turn every other
     * punctuation run into a single space. Word boundaries are preserved.
     * <p>{@code "Don't Stop Me Now!"} → {@code "dont stop me now"}
     */
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        String s = input.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : SPECIAL_LETTERS.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        s = Normalizer.normalize(s, Normalizer.Form.NFD);
        s = COMBINING_MARKS.matcher(s).replaceAll("");
        s = s.replace("&", " and ").replace("$", "s"); // A$AP, Ke$ha
        s = APOSTROPHES.matcher(s).replaceAll("");
        s = NON_ALNUM.matcher(s).replaceAll(" ").trim();
        return s;
    }

    /**
     * Matching key: {@link #normalize} plus removal of a leading "the",
     * the word "and", and all spaces.
     * <p>{@code "The Weeknd"} → {@code "weeknd"}, {@code "JAY-Z"} → {@code "jayz"},
     * {@code "Simon & Garfunkel"} → {@code "simongarfunkel"}
     */
    public static String compact(String input) {
        String s = normalize(input);
        s = LEADING_THE.matcher(s).replaceFirst("");
        s = AND_WORD.matcher(s).replaceAll(" ");
        return s.replace(" ", "");
    }
}
