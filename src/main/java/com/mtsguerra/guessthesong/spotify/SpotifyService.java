package com.mtsguerra.guessthesong.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mtsguerra.guessthesong.engine.TextNormalizer;
import com.mtsguerra.guessthesong.model.Track;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a playlist from the Spotify Web API and converts its entries into {@link Track}s.
 * <p>Uses {@code GET /playlists/{id}/items} (Feb 2026 name) and falls back to the legacy
 * {@code /tracks} path; understands both the new {@code item} and old {@code track} JSON shapes.
 */
public final class SpotifyService {

    private static final String API = "https://api.spotify.com/v1";
    private static final Pattern ID_IN_URL = Pattern.compile("playlist[/:]([A-Za-z0-9]{22})");
    private static final Pattern RAW_ID = Pattern.compile("^[A-Za-z0-9]{22}$");
    private static final long MIN_TRACK_MS = 30_000;
    private static final int MAX_RETRIES = 5;

    public record Playlist(String id, String name, String owner, List<Track> tracks) {
    }

    /** Non-2xx answer from the Web API. */
    public static class SpotifyApiException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int status;

        public SpotifyApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** The playlist exists (or might) but this token isn't allowed to read its tracks. */
    public static final class PlaylistAccessException extends SpotifyApiException {
        private static final long serialVersionUID = 1L;

        public PlaylistAccessException(int status, String message) {
            super(status, message);
        }
    }

    private final HttpClient http;
    private final TokenProvider tokens;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpotifyService(HttpClient http, TokenProvider tokens) {
        this.http = Objects.requireNonNull(http);
        this.tokens = Objects.requireNonNull(tokens);
    }

    /**
     * Accepts {@code https://open.spotify.com/playlist/{id}?si=…},
     * {@code https://open.spotify.com/intl-pt/playlist/{id}}, {@code spotify:playlist:{id}} or a bare ID.
     */
    public static String extractPlaylistId(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("No playlist URL or ID given.");
        }
        String trimmed = input.trim();
        Matcher m = ID_IN_URL.matcher(trimmed);
        if (m.find()) {
            return m.group(1);
        }
        if (RAW_ID.matcher(trimmed).matches()) {
            return trimmed;
        }
        throw new IllegalArgumentException("Not a Spotify playlist URL or ID: " + input);
    }

    public Playlist fetchPlaylist(String urlOrId) throws IOException, InterruptedException {
        String id = extractPlaylistId(urlOrId);

        JsonNode meta;
        try {
            meta = getJson(API + "/playlists/" + id + "?fields=name,owner(display_name)");
        } catch (SpotifyApiException e) {
            if (e.status() == 404 || e.status() == 403) {
                throw new PlaylistAccessException(e.status(),
                        "Spotify says this playlist doesn't exist or isn't accessible with " + tokens.description()
                                + ". Spotify-made editorial playlists (IDs starting with 37i9) are blocked for "
                                + "developer apps; copy the songs into a playlist you own.");
            }
            throw e;
        }
        String name = meta.path("name").asText("(untitled)");
        String owner = meta.path("owner").path("display_name").asText("?");

        List<Track> tracks = fetchTracks(id);
        if (tracks.isEmpty()) {
            throw new PlaylistAccessException(200,
                    "Spotify returned the playlist \"" + name + "\" but no tracks. Since February 2026, developer "
                            + "apps only see the contents of playlists the logged-in user owns or collaborates on.");
        }
        return new Playlist(id, name, owner, tracks);
    }

    private List<Track> fetchTracks(String playlistId) throws IOException, InterruptedException {
        String next = API + "/playlists/" + playlistId + "/items?limit=50&additional_types=track";
        boolean triedLegacy = false;
        Map<String, Track> byKey = new LinkedHashMap<>();

        while (next != null) {
            JsonNode page;
            try {
                page = getJson(next);
            } catch (SpotifyApiException e) {
                if (e.status() == 404 && !triedLegacy && byKey.isEmpty()) {
                    triedLegacy = true;
                    next = API + "/playlists/" + playlistId + "/tracks?limit=50&additional_types=track";
                    continue;
                }
                if (e.status() == 403 || e.status() == 404) {
                    throw new PlaylistAccessException(e.status(),
                            "Not allowed to read this playlist's tracks with " + tokens.description() + ".");
                }
                throw e;
            }

            for (JsonNode entry : page.path("items")) {
                parseEntry(entry).ifPresent(t -> {
                    String key = TextNormalizer.compact(t.title()) + "|" + TextNormalizer.compact(t.mainArtist());
                    byKey.putIfAbsent(key, t); // drops duplicates (same song added twice, remaster + original…)
                });
            }
            JsonNode nextNode = page.path("next");
            next = nextNode.isTextual() && !nextNode.asText().isBlank() ? nextNode.asText() : null;
        }
        return new ArrayList<>(byKey.values());
    }

    /** Converts one playlist entry into a Track, skipping local files, podcasts and broken entries. */
    static Optional<Track> parseEntry(JsonNode entry) {
        if (entry == null || entry.isNull()) {
            return Optional.empty();
        }
        JsonNode t = entry.hasNonNull("item") ? entry.get("item") : entry.get("track");
        if (t == null || t.isNull() || !t.isObject()) {
            return Optional.empty();
        }
        if (entry.path("is_local").asBoolean(false) || t.path("is_local").asBoolean(false)) {
            return Optional.empty();
        }
        if (!"track".equals(t.path("type").asText("track"))) {
            return Optional.empty(); // podcast episode
        }

        String rawName = t.path("name").asText("").trim();
        long durationMs = t.path("duration_ms").asLong(0);
        List<String> artists = new ArrayList<>();
        for (JsonNode a : t.path("artists")) {
            String n = a.path("name").asText("").trim();
            if (!n.isEmpty()) {
                artists.add(n);
            }
        }
        if (rawName.isEmpty() || artists.isEmpty() || durationMs < MIN_TRACK_MS) {
            return Optional.empty();
        }

        TitleCleaner.CleanTitle clean = TitleCleaner.clean(rawName);
        String main = artists.get(0);

        // Spotify's artist array is authoritative; title parsing is only a fallback.
        List<String> candidates = artists.size() > 1 ? artists.subList(1, artists.size()) : clean.featuredArtists();
        String mainKey = TextNormalizer.compact(main);
        Set<String> seen = new LinkedHashSet<>();
        List<String> featured = new ArrayList<>();
        for (String f : candidates) {
            String key = TextNormalizer.compact(f);
            if (!key.isEmpty() && !key.equals(mainKey) && seen.add(key)) {
                featured.add(f);
            }
        }

        return Optional.of(new Track(t.path("id").asText(""), clean.title(), rawName, main, featured, durationMs));
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        boolean refreshedToken = false;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + tokens.accessToken())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                return mapper.readTree(response.body());
            }
            if (status == 401 && !refreshedToken) {
                tokens.invalidate();
                refreshedToken = true;
                continue;
            }
            if (status == 429) {
                long wait = response.headers().firstValueAsLong("Retry-After").orElse(2);
                if (wait > 60 || response.body().contains("QUOTA_EXCEEDED")) {
                    throw new SpotifyApiException(429, "Spotify rate limit / quota exceeded. Try again later.");
                }
                Thread.sleep(Duration.ofSeconds(Math.max(1, wait)));
                continue;
            }
            if (status >= 500) {
                Thread.sleep(Duration.ofSeconds(1L << attempt));
                continue;
            }
            throw new SpotifyApiException(status, "Spotify API error " + status + " for " + url + ": " + response.body());
        }
        throw new SpotifyApiException(503, "Spotify API kept failing for " + url);
    }
}
