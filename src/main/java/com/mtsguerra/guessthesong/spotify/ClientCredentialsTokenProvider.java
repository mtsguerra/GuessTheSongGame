package com.mtsguerra.guessthesong.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * Spotify Client Credentials flow: app-only token, no user login.
 * <p>Note: since Spotify's Feb 2026 Development Mode changes, this token can usually read a
 * playlist's name but not its tracks unless your account owns/collaborates on it.
 */
public final class ClientCredentialsTokenProvider implements TokenProvider {

    static final URI TOKEN_URI = URI.create("https://accounts.spotify.com/api/token");

    private final HttpClient http;
    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper mapper = new ObjectMapper();

    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public ClientCredentialsTokenProvider(HttpClient http, String clientId, String clientSecret) {
        this.http = Objects.requireNonNull(http);
        this.clientId = Objects.requireNonNull(clientId);
        this.clientSecret = Objects.requireNonNull(clientSecret);
    }

    @Override
    public synchronized String accessToken() throws IOException, InterruptedException {
        if (token != null && Instant.now().isBefore(expiresAt)) {
            return token;
        }
        String basic = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(TOKEN_URI)
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Spotify token request failed (HTTP " + response.statusCode() + "). "
                    + "Check SPOTIFY_CLIENT_ID / SPOTIFY_CLIENT_SECRET. Body: " + response.body());
        }

        JsonNode json = mapper.readTree(response.body());
        token = json.path("access_token").asText();
        long expiresIn = json.path("expires_in").asLong(3600);
        expiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
        return token;
    }

    @Override
    public synchronized void invalidate() {
        token = null;
        expiresAt = Instant.EPOCH;
    }

    @Override
    public String description() {
        return "Client Credentials";
    }
}
