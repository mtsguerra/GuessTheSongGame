package com.mtsguerra.guessthesong.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mtsguerra.guessthesong.model.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleCleanerTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "Bohemian Rhapsody - Remastered 2011          | Bohemian Rhapsody",
            "Hello - Live at Wembley                      | Hello",
            "Let It Go - From \"Frozen\"/Soundtrack Version | Let It Go",
            "Lose Yourself - From \"8 Mile\" Soundtrack     | Lose Yourself",
            "Thriller (Deluxe Edition)                    | Thriller",
            "Heroes [2017 Remaster]                       | Heroes",
            "Wonderwall (Remastered)                      | Wonderwall",
            "Mr. Brightside                               | Mr. Brightside",
            "1999                                         | 1999",
            "Part 1 - Part 2                              | Part 1 - Part 2",
            "Under Pressure - Remastered 2011             | Under Pressure",
    })
    void stripsNoise(String raw, String expected) {
        assertEquals(expected, TitleCleaner.clean(raw).title());
    }

    @Test
    void handlesApostrophes() {
        assertEquals("Don't Stop Me Now", TitleCleaner.clean("Don't Stop Me Now - 2011 Mix").title());
        assertEquals("Love Story", TitleCleaner.clean("Love Story (Taylor's Version)").title());
    }

    @Test
    void extractsBracketedFeatures() {
        TitleCleaner.CleanTitle c = TitleCleaner.clean("Señorita (feat. Camila Cabello)");
        assertEquals("Señorita", c.title());
        assertEquals(List.of("Camila Cabello"), c.featuredArtists());
    }

    @Test
    void extractsWithAndMultipleNames() {
        TitleCleaner.CleanTitle c = TitleCleaner.clean("Work (with Drake & Future) - Remastered");
        assertEquals("Work", c.title());
        assertEquals(List.of("Drake", "Future"), c.featuredArtists());
    }

    @Test
    void extractsTrailingFeat() {
        TitleCleaner.CleanTitle c = TitleCleaner.clean("Old Town Road feat. Billy Ray Cyrus - Remix");
        assertEquals("Old Town Road", c.title());
        assertEquals(List.of("Billy Ray Cyrus"), c.featuredArtists());
    }

    // ---- SpotifyService helpers -------------------------------------------------

    @ParameterizedTest
    @CsvSource({
            "https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123",
            "https://open.spotify.com/intl-pt/playlist/37i9dQZF1DXcBWIGoYBM5M",
            "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
            "37i9dQZF1DXcBWIGoYBM5M",
    })
    void extractsPlaylistId(String input) {
        assertEquals("37i9dQZF1DXcBWIGoYBM5M", SpotifyService.extractPlaylistId(input));
    }

    @Test
    void rejectsNonPlaylistUrls() {
        assertThrows(IllegalArgumentException.class,
                () -> SpotifyService.extractPlaylistId("https://open.spotify.com/track/abc"));
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesNew2026ItemShape() throws Exception {
        JsonNode entry = mapper.readTree("""
                {"item": {"type":"track","id":"t1","name":"Stay - Remastered 2021","duration_ms":141000,
                          "artists":[{"name":"The Kid LAROI"},{"name":"Justin Bieber"}]}}""");
        Optional<Track> t = SpotifyService.parseEntry(entry);
        assertTrue(t.isPresent());
        assertEquals("Stay", t.get().title());
        assertEquals("The Kid LAROI", t.get().mainArtist());
        assertEquals(List.of("Justin Bieber"), t.get().featuredArtists());
        assertEquals(141000, t.get().durationMs());
    }

    @Test
    void parsesLegacyTrackShapeAndFallsBackToTitleFeatures() throws Exception {
        JsonNode entry = mapper.readTree("""
                {"track": {"type":"track","id":"t2","name":"Señorita (feat. Camila Cabello)","duration_ms":190000,
                           "artists":[{"name":"Shawn Mendes"}]}}""");
        Track t = SpotifyService.parseEntry(entry).orElseThrow();
        assertEquals("Señorita", t.title());
        assertEquals(List.of("Camila Cabello"), t.featuredArtists());
    }

    @Test
    void skipsLocalFilesAndEpisodes() throws Exception {
        assertTrue(SpotifyService.parseEntry(mapper.readTree("""
                {"is_local": true, "track": {"type":"track","name":"x","duration_ms":100000,"artists":[{"name":"a"}]}}""")).isEmpty());
        assertTrue(SpotifyService.parseEntry(mapper.readTree("""
                {"item": {"type":"episode","name":"Podcast","duration_ms":3000000,"artists":[]}}""")).isEmpty());
        assertTrue(SpotifyService.parseEntry(mapper.readTree("{\"track\": null}")).isEmpty());
    }
}
