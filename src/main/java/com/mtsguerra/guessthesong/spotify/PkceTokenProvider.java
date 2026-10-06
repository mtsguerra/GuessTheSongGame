package com.mtsguerra.guessthesong.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Authorization Code + PKCE flow: the user logs in once in the browser, a tiny local HTTP
 * server on the redirect URI catches the code, and the refresh token is cached on disk
 * (in {@code .spotify-token-cache}, which is git-ignored) so later runs skip the browser.
 * <p>No client secret is needed for PKCE.
 */
public final class PkceTokenProvider implements TokenProvider {

    private static final URI AUTHORIZE_URI = URI.create("https://accounts.spotify.com/authorize");
    private static final String SCOPES = "playlist-read-private playlist-read-collaborative";
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(3);

    private final HttpClient http;
    private final String clientId;
    private final URI redirectUri;
    private final Path cacheFile;
    private final PrintStream out;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    private String accessToken;
    private String refreshToken;
    private Instant expiresAt = Instant.EPOCH;

    public PkceTokenProvider(HttpClient http, String clientId, URI redirectUri, Path cacheFile, PrintStream out) {
        this.http = Objects.requireNonNull(http);
        this.clientId = Objects.requireNonNull(clientId);
        this.redirectUri = Objects.requireNonNull(redirectUri);
        this.cacheFile = Objects.requireNonNull(cacheFile);
        this.out = Objects.requireNonNull(out);
        this.refreshToken = readCachedRefreshToken();
    }

    @Override
    public synchronized String accessToken() throws IOException, InterruptedException {
        if (accessToken != null && Instant.now().isBefore(expiresAt)) {
            return accessToken;
        }
        if (refreshToken != null) {
            try {
                refresh();
                return accessToken;
            } catch (IOException e) {
                out.println("  (Saved Spotify login expired — please log in again.)");
                refreshToken = null;
            }
        }
        interactiveLogin();
        return accessToken;
    }

    @Override
    public synchronized void invalidate() {
        accessToken = null;
        expiresAt = Instant.EPOCH;
    }

    @Override
    public String description() {
        return "Spotify login (PKCE)";
    }

    // ------------------------------------------------------------------ login

    private void interactiveLogin() throws IOException, InterruptedException {
        String verifier = base64Url(randomBytes(64));
        String challenge = base64Url(sha256(verifier));
        String state = base64Url(randomBytes(16));

        CompletableFuture<String> codeFuture = new CompletableFuture<>();
        HttpServer server = startCallbackServer(state, codeFuture);
        try {
            String authorizeUrl = AUTHORIZE_URI + "?" + form(Map.of(
                    "client_id", clientId,
                    "response_type", "code",
                    "redirect_uri", redirectUri.toString(),
                    "code_challenge_method", "S256",
                    "code_challenge", challenge,
                    "scope", SCOPES,
                    "state", state));

            out.println();
            out.println("  Opening Spotify login in your browser…");
            out.println("  If nothing opens, paste this URL into your browser:");
            out.println("  " + authorizeUrl);
            openBrowser(authorizeUrl);

            String code;
            try {
                code = codeFuture.get(LOGIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new IOException("Timed out waiting for Spotify login.", e);
            } catch (ExecutionException e) {
                throw new IOException("Spotify login failed: " + e.getCause().getMessage(), e.getCause());
            }

            Map<String, String> params = new HashMap<>();
            params.put("grant_type", "authorization_code");
            params.put("code", code);
            params.put("redirect_uri", redirectUri.toString());
            params.put("client_id", clientId);
            params.put("code_verifier", verifier);
            applyTokenResponse(postToken(params));
            out.println("  ✔ Logged in to Spotify.");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer startCallbackServer(String expectedState, CompletableFuture<String> codeFuture)
            throws IOException {
        int port = redirectUri.getPort() > 0 ? redirectUri.getPort() : 80;
        String path = redirectUri.getPath() == null || redirectUri.getPath().isEmpty() ? "/" : redirectUri.getPath();

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(redirectUri.getHost(), port), 0);
        } catch (IOException e) {
            throw new IOException("Could not listen on " + redirectUri
                    + " (is another program using port " + port + "?)", e);
        }

        server.createContext(path, exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String message;
            if (query.containsKey("error")) {
                message = "Spotify login was cancelled: " + query.get("error");
                codeFuture.completeExceptionally(new IOException(message));
            } else if (!expectedState.equals(query.get("state")) || !query.containsKey("code")) {
                message = "Unexpected callback (state mismatch). Please try again.";
                codeFuture.completeExceptionally(new IOException(message));
            } else {
                message = "Logged in! You can close this tab and return to the game.";
                codeFuture.complete(query.get("code"));
            }
            byte[] body = ("<html><body style='font-family:sans-serif;padding:2em'><h2>Guess the Song</h2><p>"
                    + message + "</p></body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    // --------------------------------------------------------------- refresh

    private void refresh() throws IOException, InterruptedException {
        Map<String, String> params = new HashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", refreshToken);
        params.put("client_id", clientId);
        applyTokenResponse(postToken(params));
    }

    private JsonNode postToken(Map<String, String> params) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(ClientCredentialsTokenProvider.TOKEN_URI)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(params)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Spotify token request failed (HTTP " + response.statusCode() + "): "
                    + response.body());
        }
        return mapper.readTree(response.body());
    }

    private void applyTokenResponse(JsonNode json) {
        accessToken = json.path("access_token").asText();
        expiresAt = Instant.now().plusSeconds(Math.max(60, json.path("expires_in").asLong(3600) - 60));
        String newRefresh = json.path("refresh_token").asText(null);
        if (newRefresh != null && !newRefresh.isBlank()) {
            refreshToken = newRefresh;
            writeCachedRefreshToken(newRefresh);
        }
    }

    // ----------------------------------------------------------- token cache

    private String readCachedRefreshToken() {
        try {
            if (Files.isRegularFile(cacheFile)) {
                String value = Files.readString(cacheFile).trim();
                return value.isEmpty() ? null : value;
            }
        } catch (IOException ignored) {
            // unreadable cache → just log in again
        }
        return null;
    }

    private void writeCachedRefreshToken(String value) {
        try {
            Files.writeString(cacheFile, value);
            try {
                Files.setPosixFilePermissions(cacheFile, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows
            }
        } catch (IOException e) {
            out.println("  (Could not save Spotify login: " + e.getMessage() + ")");
        }
    }

    // --------------------------------------------------------------- helpers

    private void openBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        ProcessBuilder pb;
        if (os.contains("mac")) {
            pb = new ProcessBuilder("open", url);
        } else if (os.contains("win")) {
            pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
        } else {
            pb = new ProcessBuilder("xdg-open", url);
        }
        try {
            pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException ignored) {
            // URL was already printed
        }
    }

    private byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        random.nextBytes(bytes);
        return bytes;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String form(Map<String, String> params) {
        StringJoiner joiner = new StringJoiner("&");
        params.forEach((k, v) -> joiner.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return joiner.toString();
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null) {
            return map;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }
}
