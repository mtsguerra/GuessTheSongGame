package com.mtsguerra.guessthesong.game;

import com.mtsguerra.guessthesong.audio.YouTubeAudioStreamer;
import com.mtsguerra.guessthesong.audio.YouTubeAudioStreamer.Playback;
import com.mtsguerra.guessthesong.audio.YouTubeAudioStreamer.ResolvedAudio;
import com.mtsguerra.guessthesong.engine.ScoringEngine;
import com.mtsguerra.guessthesong.model.RoundResult;
import com.mtsguerra.guessthesong.model.Track;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a 10-round game: shuffle → (resolve next song in the background) → play 10 s clip
 * with a live countdown → three prompts → score → reveal.
 */
public final class GameSession implements AutoCloseable {

    public static final int ROUNDS = 10;
    public static final int CLIP_SECONDS = 10;
    public static final String QUIT = "/quit";

    private static final Duration CLIP = Duration.ofSeconds(CLIP_SECONDS);

    private final List<Track> pool;
    private final YouTubeAudioStreamer streamer;
    private final ScoringEngine scoring;
    private final ConsoleInput input;
    private final PrintStream out;
    private final Random random;

    private final ExecutorService prefetcher = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "countdown");
        t.setDaemon(true);
        return t;
    });

    private final List<RoundResult> results = new ArrayList<>();

    public GameSession(List<Track> pool, YouTubeAudioStreamer streamer, ScoringEngine scoring,
                       ConsoleInput input, PrintStream out, Random random) {
        if (pool.size() < ROUNDS) {
            throw new IllegalArgumentException("Need at least " + ROUNDS + " tracks, got " + pool.size());
        }
        this.pool = List.copyOf(pool);
        this.streamer = Objects.requireNonNull(streamer);
        this.scoring = Objects.requireNonNull(scoring);
        this.input = Objects.requireNonNull(input);
        this.out = Objects.requireNonNull(out);
        this.random = Objects.requireNonNull(random);
    }

    /** Plays the whole game and returns the per-round results. */
    public List<RoundResult> play() throws InterruptedException {
        List<Track> deck = new ArrayList<>(pool);
        Collections.shuffle(deck, random); // each track used at most once
        Iterator<Track> remaining = deck.iterator();

        printRules();

        CompletableFuture<ResolvedAudio> next = prefetch(remaining);
        int round = 1;
        try {
            while (round <= ROUNDS && next != null) {
                ResolvedAudio audio = await(next);
                next = prefetch(remaining); // look up the following song while this one plays

                if (audio == null) {
                    continue; // couldn't find it on YouTube → silently draw another track
                }
                RoundResult result = playRound(round, audio);
                if (result != null) {
                    results.add(result);
                    round++;
                }
            }
        } catch (QuitException e) {
            out.println();
            out.println("  Game ended early.");
        }

        if (round <= ROUNDS && next == null && results.size() < ROUNDS) {
            out.println("  Ran out of playable tracks after " + results.size() + " rounds.");
        }
        printSummary();
        return List.copyOf(results);
    }

    // ================================================================ a round

    private RoundResult playRound(int round, ResolvedAudio audio) throws InterruptedException {
        Track track = audio.track();
        out.println();
        out.println("──────────────────────────────────────────────");
        out.printf("  ROUND %d / %d%n", round, ROUNDS);
        out.println("──────────────────────────────────────────────");
        out.println("  Get ready… (press Enter to stop the clip early)");

        double offset = streamer.pickOffsetSeconds(audio, CLIP_SECONDS);
        input.discardPending();
        Playback playback = streamer.play(audio, offset, CLIP);

        try {
            playback.started().get(35, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            playback.stop();
            out.println("  ⚠ Couldn't play this one (" + rootMessage(e) + "). Picking another song…");
            return null;
        }

        String earlyLine = listenWithCountdown(playback);

        out.println();
        String titleGuess;
        if (earlyLine != null && !earlyLine.isBlank()) {
            titleGuess = earlyLine.trim();
            out.println("  1) Title: " + titleGuess);
        } else {
            titleGuess = prompt("  1) Title: ");
        }
        String artistGuess = prompt("  2) Main artist: ");
        String featuredGuess = prompt("  3) Featured artist(s) (Enter if none): ");

        RoundResult result = scoring.score(round, track, titleGuess, artistGuess, featuredGuess);
        printReveal(result, audio);
        return result;
    }

    /**
     * Shows a 10 → 0 countdown (on the ticker thread) while the audio plays (on its own virtual
     * thread). The main thread only polls the input queue, so typing is never blocked.
     *
     * @return a line the player typed during the clip, or null
     */
    private String listenWithCountdown(Playback playback) throws InterruptedException {
        AtomicInteger secondsLeft = new AtomicInteger(CLIP_SECONDS);
        ScheduledFuture<?> countdown = ticker.scheduleAtFixedRate(() -> {
            int s = secondsLeft.getAndDecrement();
            if (s >= 0) {
                out.print("\r  ♪ " + bar(s) + " " + String.format("%2ds ", s));
                out.flush();
            }
        }, 0, 1, TimeUnit.SECONDS);

        String typed = null;
        try {
            while (!playback.finished().isDone()) {
                Optional<String> line = input.poll(Duration.ofMillis(100));
                if (line.isPresent()) {
                    typed = line.get();
                    playback.stop();
                    break;
                }
            }
        } finally {
            countdown.cancel(false);
            try {
                playback.finished().get(2, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException ignored) {
                // a mid-clip failure still lets the player guess what they heard
            }
        }
        out.print("\r  ♪ " + bar(0) + "  ⏹  ");
        out.flush();

        if (typed != null && typed.trim().equalsIgnoreCase(QUIT)) {
            throw new QuitException();
        }
        return typed;
    }

    private String prompt(String label) throws InterruptedException {
        out.print(label);
        out.flush();
        String line = input.readLine();
        if (line == null || line.trim().equalsIgnoreCase(QUIT)) {
            throw new QuitException();
        }
        return line.trim();
    }

    // ============================================================ prefetching

    private CompletableFuture<ResolvedAudio> prefetch(Iterator<Track> remaining) {
        if (!remaining.hasNext()) {
            return null;
        }
        Track track = remaining.next();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return streamer.resolve(track);
            } catch (IOException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }, prefetcher);
    }

    private ResolvedAudio await(CompletableFuture<ResolvedAudio> future) throws InterruptedException {
        if (!future.isDone()) {
            out.println();
            out.println("  Finding the next song…");
        }
        try {
            return future.get(90, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            future.cancel(true);
            return null;
        }
    }

    // ================================================================ output

    private void printRules() {
        out.println();
        out.println("  " + ROUNDS + " rounds · " + CLIP_SECONDS + "-second clips · max "
                + ScoringEngine.MAX_POINTS_PER_ROUND * ROUNDS + " points");
        out.println("  Title +" + ScoringEngine.TITLE_POINTS
                + "  ·  Main artist +" + ScoringEngine.MAIN_ARTIST_POINTS
                + "  ·  Featured artist(s) +" + ScoringEngine.FEATURED_POINTS + " (free if there are none)");
        out.println("  Small typos and accents are forgiven. Type " + QUIT + " at any prompt to stop.");
    }

    private void printReveal(RoundResult r, ResolvedAudio audio) {
        Track t = r.track();
        int total = results.stream().mapToInt(RoundResult::points).sum() + r.points();
        out.println();
        out.println("  ▶ " + t.displayName());
        out.printf("    %s Title        %-28s (+%d)%n", mark(r.titleCorrect()), t.title(),
                r.titleCorrect() ? ScoringEngine.TITLE_POINTS : 0);
        out.printf("    %s Main artist  %-28s (+%d)%n", mark(r.mainArtistCorrect()), t.mainArtist(),
                r.mainArtistCorrect() ? ScoringEngine.MAIN_ARTIST_POINTS : 0);
        out.printf("    %s Featured     %-28s (+%d)%n", mark(r.featuredCorrect()), t.featuredDisplay(),
                r.featuredCorrect() ? ScoringEngine.FEATURED_POINTS : 0);
        out.printf("    Round: %d/%d   ·   Total: %d/%d%n", r.points(), ScoringEngine.MAX_POINTS_PER_ROUND,
                total, ScoringEngine.MAX_POINTS_PER_ROUND * ROUNDS);
        out.println("    (clip from youtube.com/watch?v=" + audio.videoId() + ")");
    }

    private void printSummary() {
        int total = results.stream().mapToInt(RoundResult::points).sum();
        int max = ScoringEngine.MAX_POINTS_PER_ROUND * ROUNDS;
        out.println();
        out.println("══════════════════════════════════════════════");
        out.println("  FINAL SCORE: " + total + " / " + max);
        out.println("══════════════════════════════════════════════");
        for (RoundResult r : results) {
            out.printf("  %2d. %d/5  %s%n", r.round(), r.points(), r.track().displayName());
        }
        out.println();
        out.println("  " + verdict(total, max));
    }

    private static String verdict(int total, int max) {
        double pct = max == 0 ? 0 : (double) total / max;
        if (pct >= 0.9) {
            return "Legendary ears. 🎧";
        }
        if (pct >= 0.7) {
            return "Great run!";
        }
        if (pct >= 0.4) {
            return "Not bad — play it again?";
        }
        return "Tough playlist! Try another round.";
    }

    private static String bar(int secondsLeft) {
        int filled = Math.max(0, Math.min(CLIP_SECONDS, secondsLeft));
        return "█".repeat(filled) + "░".repeat(CLIP_SECONDS - filled);
    }

    private static String mark(boolean ok) {
        return ok ? "✔" : "✘";
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    @Override
    public void close() {
        prefetcher.shutdownNow();
        ticker.shutdownNow();
    }

    /** Thrown when the player types /quit or closes stdin. */
    private static final class QuitException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        QuitException() {
            super(null, null, false, false);
        }
    }
}
