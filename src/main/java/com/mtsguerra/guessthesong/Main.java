package com.mtsguerra.guessthesong;

import com.mtsguerra.guessthesong.audio.YouTubeAudioStreamer;
import com.mtsguerra.guessthesong.engine.ScoringEngine;
import com.mtsguerra.guessthesong.game.ConsoleInput;
import com.mtsguerra.guessthesong.game.GameSession;
import com.mtsguerra.guessthesong.spotify.ClientCredentialsTokenProvider;
import com.mtsguerra.guessthesong.spotify.PkceTokenProvider;
import com.mtsguerra.guessthesong.spotify.SpotifyService;
import com.mtsguerra.guessthesong.spotify.SpotifyService.Playlist;
import com.mtsguerra.guessthesong.spotify.SpotifyService.PlaylistAccessException;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;

/**
 * Entry point.
 * <pre>
 * Env vars:
 *   SPOTIFY_CLIENT_ID       (required)
 *   SPOTIFY_CLIENT_SECRET   (required for Client Credentials)
 *   SPOTIFY_AUTH            auto (default) | client | pkce
 *   SPOTIFY_REDIRECT_URI    default http://127.0.0.1:8888/callback
 *   YTDLP_PATH / FFMPEG_PATH  optional full paths
 * Args:
 *   [playlist URL or ID]    asked interactively if omitted
 * </pre>
 */
public final class Main {

    private static final String DEFAULT_REDIRECT = "http://127.0.0.1:8888/callback";

    private Main() {
    }

    public static void main(String[] args) {
        int exit;
        try {
            exit = run(args, System.out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exit = 130;
        }
        System.exit(exit);
    }

    static int run(String[] args, PrintStream out) throws InterruptedException {
        out.println();
        out.println("  ══════════════════════════════════════════════");
        out.println("                 ♪  GUESS THE SONG  ♪");
        out.println("  ══════════════════════════════════════════════");

        String clientId = env("SPOTIFY_CLIENT_ID");
        String clientSecret = env("SPOTIFY_CLIENT_SECRET");
        String authMode = env("SPOTIFY_AUTH").isEmpty() ? "auto" : env("SPOTIFY_AUTH").toLowerCase(Locale.ROOT);

        if (clientId.isEmpty() || (clientSecret.isEmpty() && !authMode.equals("pkce"))) {
            out.println();
            out.println("  Missing Spotify credentials.");
            out.println("  Set SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET in your Run Configuration");
            out.println("  (Run → Edit Configurations → Environment variables).");
            return 1;
        }

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        try (ConsoleInput input = new ConsoleInput(System.in);
             YouTubeAudioStreamer streamer = new YouTubeAudioStreamer()) {

            out.println();
            out.print("  Checking yt-dlp, ffmpeg and audio output… ");
            streamer.verifyDependencies();
            out.println("OK");

            String playlistArg = args.length > 0 ? args[0] : null;
            while (playlistArg == null || playlistArg.isBlank()) {
                out.print("  Paste a Spotify playlist URL: ");
                out.flush();
                playlistArg = input.readLine();
                if (playlistArg == null) {
                    return 0;
                }
            }

            Playlist playlist = loadPlaylist(playlistArg, authMode, http, clientId, clientSecret, input, out);
            if (playlist == null) {
                return 1;
            }

            out.printf("  Loaded \"%s\" by %s — %d usable tracks.%n",
                    playlist.name(), playlist.owner(), playlist.tracks().size());
            if (playlist.tracks().size() < GameSession.ROUNDS) {
                out.println("  This playlist needs at least " + GameSession.ROUNDS + " playable tracks.");
                return 1;
            }

            try (GameSession session = new GameSession(playlist.tracks(), streamer, new ScoringEngine(),
                    input, out, new SecureRandom())) {
                session.play();
            }
            return 0;

        } catch (IllegalArgumentException e) {
            out.println();
            out.println("  " + e.getMessage());
            return 1;
        } catch (IOException e) {
            out.println();
            out.println("  ✘ " + e.getMessage());
            return 1;
        }
    }

    /** Client Credentials first (as requested); falls back to a one-time browser login if Spotify hides the tracks. */
    private static Playlist loadPlaylist(String playlistArg, String authMode, HttpClient http,
                                         String clientId, String clientSecret,
                                         ConsoleInput input, PrintStream out)
            throws IOException, InterruptedException {

        String playlistId = SpotifyService.extractPlaylistId(playlistArg); // validates early

        if (!authMode.equals("pkce")) {
            out.println("  Loading playlist with Client Credentials…");
            try {
                return new SpotifyService(http, new ClientCredentialsTokenProvider(http, clientId, clientSecret))
                        .fetchPlaylist(playlistId);
            } catch (PlaylistAccessException e) {
                out.println();
                out.println("  ⚠ " + e.getMessage());
                if (authMode.equals("client")) {
                    return null;
                }
                out.println("  Logging in with your Spotify account lets the game read playlists you own or collaborate on.");
                out.print("  Log in now? [Y/n] ");
                out.flush();
                String answer = input.readLine();
                if (answer == null || answer.trim().toLowerCase(Locale.ROOT).startsWith("n")) {
                    return null;
                }
            }
        }

        String redirect = env("SPOTIFY_REDIRECT_URI").isEmpty() ? DEFAULT_REDIRECT : env("SPOTIFY_REDIRECT_URI");
        PkceTokenProvider pkce = new PkceTokenProvider(http, clientId, URI.create(redirect),
                Path.of(".spotify-token-cache"), out);
        try {
            return new SpotifyService(http, pkce).fetchPlaylist(playlistId);
        } catch (PlaylistAccessException e) {
            out.println();
            out.println("  ⚠ " + e.getMessage());
            out.println("  Tip: open the playlist in Spotify → ⋯ → Add to other playlist → New playlist,");
            out.println("  then paste the URL of your copy.");
            return null;
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.trim();
    }
}
