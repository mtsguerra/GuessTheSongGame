package com.mtsguerra.guessthesong.engine;

import com.mtsguerra.guessthesong.model.RoundResult;
import com.mtsguerra.guessthesong.model.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScoringEngineTest {

    private final ScoringEngine engine = new ScoringEngine();

    private static Track track(String title, String main, String... featured) {
        return new Track("id", title, title, main, List.of(featured), 200_000);
    }

    @Test
    void levenshteinBasics() {
        assertEquals(3, Levenshtein.distance("kitten", "sitting"));
        assertEquals(0, Levenshtein.distance("abc", "abc"));
        assertEquals(3, Levenshtein.distance("", "abc"));
        assertEquals(1.0, Levenshtein.similarity("", ""));
    }

    @Test
    void normalizerStripsAccentsAndPunctuation() {
        assertEquals("dont stop me now", TextNormalizer.normalize("Don't Stop Me Now!"));
        assertEquals("beyonce", TextNormalizer.normalize("Beyoncé"));
        assertEquals("weeknd", TextNormalizer.compact("The Weeknd"));
        assertEquals("simongarfunkel", TextNormalizer.compact("Simon & Garfunkel"));
    }

    @ParameterizedTest(name = "\"{0}\" matches \"{1}\"")
    @CsvSource({
            "senorita,               Señorita",
            "beyonce,                Beyoncé",
            "BEYONCÉ!!,              Beyoncé",
            "jay z,                  JAY-Z",
            "weeknd,                 The Weeknd",
            "simon and garfunkel,    Simon & Garfunkel",
            "simon garfunkel,        Simon & Garfunkel",
            "bohemain rhapsody,      Bohemian Rhapsody",
            "smels lik ten spirit,   Smells Like Teen Spirit",
            "sigur ros,              Sigur Rós",
            "mo,                     MØ",
            "asap rocky,             A$AP Rocky",
    })
    void fuzzyMatches(String guess, String answer) {
        assertTrue(engine.isMatch(guess, answer));
    }

    @ParameterizedTest(name = "\"{0}\" does NOT match \"{1}\"")
    @CsvSource({
            "no,              Up",
            "Hey,             Hello",
            "Drake,           Future",
            "'',              Hello",
            "bohemian,        Bohemian Rhapsody",
    })
    void rejectsWrongGuesses(String guess, String answer) {
        assertFalse(engine.isMatch(guess, answer));
    }

    @Test
    void apostrophesAreIgnored() {
        assertTrue(engine.isMatch("dont stop me now", "Don't Stop Me Now"));
        assertTrue(engine.isMatch("its my life", "It’s My Life"));
    }

    @Test
    void perfectRoundIsFivePoints() {
        Track t = track("Blinding Lights", "The Weeknd");
        RoundResult r = engine.score(1, t, "blinding lights", "weeknd", "");
        assertEquals(5, r.points());
    }

    @Test
    void noFeaturesAutoAwardsOnePoint() {
        Track t = track("Blinding Lights", "The Weeknd");
        RoundResult r = engine.score(1, t, "wrong", "wrong", "");
        assertTrue(r.featuredCorrect());
        assertEquals(1, r.points());
    }

    @Test
    void featuresMustAllBeNamedInAnyOrder() {
        Track t = track("Work", "Rihanna", "Drake", "Future");
        assertTrue(engine.featuredMatches("future & drake", t));
        assertTrue(engine.featuredMatches("Drake, Future", t));
        assertTrue(engine.featuredMatches("drak and futur", t));
        assertFalse(engine.featuredMatches("drake", t));
        assertFalse(engine.featuredMatches("", t));
    }

    @Test
    void featuredNameContainingAndSurvivesSplitting() {
        Track t = track("Song", "Someone", "Florence + the Machine");
        assertTrue(engine.featuredMatches("florence and the machine", t));
    }

    @Test
    void scoreBreakdown() {
        Track t = track("Stay", "The Kid LAROI", "Justin Bieber");
        RoundResult r = engine.score(3, t, "stay", "kid laroi", "nobody");
        assertTrue(r.titleCorrect());
        assertTrue(r.mainArtistCorrect());
        assertFalse(r.featuredCorrect());
        assertEquals(4, r.points());
        assertEquals(3, r.round());
    }

    @Test
    void titleWithLeftoverBracketsAcceptsShortForm() {
        Track t = track("Bad Guy (Billie Mix)", "Billie Eilish");
        assertTrue(engine.titleMatches("bad guy", t));
    }
}
