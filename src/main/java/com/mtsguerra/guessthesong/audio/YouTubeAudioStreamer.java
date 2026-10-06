package com.mtsguerra.guessthesong.audio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mtsguerra.guessthesong.engine.TextNormalizer;
import com.mtsguerra.guessthesong.model.Track;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds a song on YouTube with yt-dlp and plays a short clip of it.
 * <pre>
 *  yt-dlp "ytsearch1:{title} {artist} lyrics"  → direct audio URL + duration + HTTP headers
 *  ffmpeg -ss {offset} -i {url} -t 10 -f s16le  → raw 44.1 kHz stereo PCM on stdout
 *  Java SourceDataLine                          → speakers (exactly 10.0 s of samples)
 * </pre>
 * Playback runs on a virtual thread; a scheduler enforces a hard wall-clock cutoff.
 */
public final class YouTubeAudioStreamer implements AutoCloseable {

    public static final int SAMPLE_RATE = 44_100;
    public static final int CHANNELS = 2;
    public static final int FRAME_BYTES = CHANNELS * 2; // 16-bit
    private static final AudioFormat PCM = new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);

    private static final int EDGE_SECONDS = 15;
    private static final long RESOLVE_TIMEOUT_SECONDS = 45;
    private static final long STARTUP_TIMEOUT_SECONDS = 30;
    private static final double MIN_VIDEO_SECONDS = 45;
    private static final double MAX_VIDEO_SECONDS = 15 * 60;

    /** What yt-dlp found for a track. */
    public record ResolvedAudio(
            Track track,
            String videoId,
            String videoTitle,
            double durationSeconds,
            String streamUrl,
            Map<String, String> httpHeaders,
            String query) {

        /** YouTube duration if known, otherwise Spotify's. */
        public double effectiveDurationSeconds() {
            return durationSeconds > 0 ? durationSeconds : track.durationMs() / 1000.0;
        }
    }

    private final String ytDlp;
    private final String ffmpeg;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "audio-watchdog");
        t.setDaemon(true);
        return t;
    });

    public YouTubeAudioStreamer() {
        this(locate("yt-dlp", "YTDLP_PATH"), locate("ffmpeg", "FFMPEG_PATH"));
    }

    public YouTubeAudioStreamer(String ytDlpPath, String ffmpegPath) {
        this.ytDlp = Objects.requireNonNull(ytDlpPath);
        this.ffmpeg = Objects.requireNonNull(ffmpegPath);
    }

    // ============================================================ setup checks

    /** Fails fast with a helpful message if yt-dlp / ffmpeg / an audio device is missing. */
    public void verifyDependencies() throws IOException, InterruptedException {
        checkTool(List.of(ytDlp, "--version"), "yt-dlp", "brew install yt-dlp deno");
        checkTool(List.of(ffmpeg, "-version"), "ffmpeg", "brew install ffmpeg");
        if (!AudioSystem.isLineSupported(new javax.sound.sampled.DataLine.Info(SourceDataLine.class, PCM))) {
            throw new IOException("No audio output device supports 44.1 kHz 16-bit stereo PCM.");
        }
    }

    private void checkTool(List<String> cmd, String name, String installHint)
            throws IOException, InterruptedException {
        try {
            ProcessResult r = run(cmd, 15);
            if (r.exitCode() != 0) {
                throw new IOException(name + " exited with code " + r.exitCode() + ": " + r.stderr());
            }
        } catch (IOException e) {
            throw new IOException("Could not run " + name + " (" + cmd.get(0) + "). Install it with: "
                    + installHint + "  — or set " + name.toUpperCase(Locale.ROOT).replace("-", "")
                    + "_PATH to its full path. Cause: " + e.getMessage(), e);
        }
    }

    // ============================================================= resolving

    /**
     * Searches "{title} {artist} lyrics", then "{title} {artist} official audio".
     * Prefers a result whose duration is plausible and whose video title mentions the song.
     */
    public ResolvedAudio resolve(Track track) throws IOException, InterruptedException {
        String base = track.title() + " " + track.mainArtist();
        List<String> queries = List.of(base + " lyrics", base + " official audio");

        ResolvedAudio fallback = null;
        IOException lastError = null;
        for (String query : queries) {
            try {
                ResolvedAudio candidate = resolveQuery(track, query);
                if (looksRight(candidate)) {
                    return candidate;
                }
                if (fallback == null) {
                    fallback = candidate;
                }
            } catch (IOException e) {
                lastError = e;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw lastError != null ? lastError : new IOException("No YouTube result for " + base);
    }

    private ResolvedAudio resolveQuery(Track track, String query) throws IOException, InterruptedException {
        List<String> cmd = List.of(ytDlp,
                "--no-playlist", "--no-warnings", "--quiet",
                "-f", "bestaudio[acodec!=none]/bestaudio/best",
                "--print", "%(id)s\t%(duration)s\t%(title)s\t%(http_headers)j\t%(url)s",
                "ytsearch1:" + query);

        ProcessResult r = run(cmd, RESOLVE_TIMEOUT_SECONDS);
        if (r.exitCode() != 0) {
            throw new IOException("yt-dlp failed for \"" + query + "\": " + firstLine(r.stderr()));
        }
        String line = r.stdout().lines().filter(l -> !l.isBlank()).reduce((a, b) -> b).orElse("");
        String[] parts = line.split("\t", 5);
        if (parts.length < 5 || parts[4].isBlank() || "NA".equals(parts[4])) {
            throw new IOException("yt-dlp returned no playable stream for \"" + query + "\"");
        }

        double duration;
        try {
            duration = Double.parseDouble(parts[1]);
        } catch (NumberFormatException e) {
            duration = 0;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (!"NA".equals(parts[3]) && !parts[3].isBlank()) {
            try {
                headers.putAll(mapper.readValue(parts[3], new TypeReference<Map<String, String>>() {
                }));
            } catch (IOException ignored) {
                // headers are optional
            }
        }
        return new ResolvedAudio(track, parts[0], parts[2], duration, parts[4].trim(), headers, query);
    }

    private static boolean looksRight(ResolvedAudio a) {
        double d = a.durationSeconds();
        boolean plausibleLength = d == 0 || (d >= MIN_VIDEO_SECONDS && d <= MAX_VIDEO_SECONDS);
        String video = TextNormalizer.compact(a.videoTitle());
        String song = TextNormalizer.compact(a.track().title());
        boolean mentionsSong = song.isEmpty() || video.contains(song);
        return plausibleLength && mentionsSong;
    }

    // =============================================================== playback

    /** Uniform random start in [15 s, duration − 15 s]; middle of the track for very short ones. */
    public double pickOffsetSeconds(ResolvedAudio audio, double clipSeconds) {
        double duration = audio.effectiveDurationSeconds();
        double lo = EDGE_SECONDS;
        double hi = duration - EDGE_SECONDS;
        if (hi - clipSeconds <= lo) { // can't fit 15 s + clip + 15 s → centre the clip
            return Math.max(0, (duration - clipSeconds) / 2);
        }
        // make sure the clip itself never runs past the end of the video
        hi = Math.min(hi, duration - clipSeconds);
        return ThreadLocalRandom.current().nextDouble(lo, hi);
    }

    /** Starts playing in the background and returns immediately. */
    public Playback play(ResolvedAudio audio, double offsetSeconds, Duration clip) {
        Playback playback = new Playback();
        Thread.ofVirtual().name("audio-playback").start(() -> stream(audio, offsetSeconds, clip, playback));

        // Safety net if ffmpeg hangs while connecting.
        long watchdogMs = TimeUnit.SECONDS.toMillis(STARTUP_TIMEOUT_SECONDS) + clip.toMillis();
        scheduler.schedule(() -> {
            if (!playback.finished.isDone()) {
                playback.fail(new IOException("Playback timed out"));
            }
        }, watchdogMs, TimeUnit.MILLISECONDS);
        return playback;
    }

    private void stream(ResolvedAudio audio, double offsetSeconds, Duration clip, Playback playback) {
        double clipSeconds = clip.toMillis() / 1000.0;
        long totalBytes = Math.round(clipSeconds * SAMPLE_RATE) * FRAME_BYTES;

        List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin"));
        if (!audio.httpHeaders().isEmpty()) {
            StringBuilder h = new StringBuilder();
            audio.httpHeaders().forEach((k, v) -> {
                if (k != null && v != null) {
                    h.append(k).append(": ").append(v).append("\r\n");
                }
            });
            cmd.addAll(List.of("-headers", h.toString()));
        }
        cmd.addAll(List.of(
                "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "2",
                "-ss", String.format(Locale.ROOT, "%.3f", offsetSeconds), // input seek: fast
                "-i", audio.streamUrl(),
                "-t", String.format(Locale.ROOT, "%.3f", clipSeconds),
                "-vn", "-ac", String.valueOf(CHANNELS), "-ar", String.valueOf(SAMPLE_RATE),
                "-f", "s16le", "-acodec", "pcm_s16le", "pipe:1"));

        try {
            Process process = new ProcessBuilder(cmd).start();
            playback.process = process;
            CompletableFuture<String> stderr = readAllAsync(process.getErrorStream());

            SourceDataLine line = AudioSystem.getSourceDataLine(PCM);
            int bufferBytes = (SAMPLE_RATE / 4) * FRAME_BYTES; // ~250 ms
            line.open(PCM, bufferBytes);
            playback.line = line;

            long written = 0;
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[8192];
                int carry = 0; // keep whole frames only
                int n;
                while (!playback.stopped.get() && written < totalBytes
                        && (n = in.read(buf, carry, buf.length - carry)) > 0) {
                    int available = carry + n;
                    int usable = (int) Math.min(available - (available % FRAME_BYTES), totalBytes - written);
                    if (usable > 0) {
                        if (written == 0) {
                            line.start();
                            playback.markStarted(clip, scheduler);
                        }
                        line.write(buf, 0, usable);
                        written += usable;
                    }
                    carry = available - usable;
                    if (carry > 0 && usable > 0) {
                        System.arraycopy(buf, usable, buf, 0, carry);
                    }
                }
            }
            if (!playback.stopped.get() && written > 0) {
                line.drain(); // let the last ~250 ms come out of the speakers
            }

            if (written == 0 && !playback.stopped.get()) {
                try {
                    process.waitFor(2, TimeUnit.SECONDS); // let stderr finish so the error is readable
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                playback.fail(new IOException("ffmpeg produced no audio. " + firstLine(stderr.getNow(""))));
            } else {
                playback.complete();
            }
        } catch (LineUnavailableException e) {
            playback.fail(new IOException("Audio device unavailable: " + e.getMessage(), e));
        } catch (IOException e) {
            playback.fail(e);
        } catch (RuntimeException e) {
            playback.fail(new IOException(e.toString(), e));
        } finally {
            playback.release();
        }
    }

    /** Handle to a running clip. */
    public static final class Playback {
        private final CompletableFuture<Void> started = new CompletableFuture<>();
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private volatile Process process;
        private volatile SourceDataLine line;
        private volatile ScheduledFuture<?> hardStop;

        /** Completes when the first samples hit the sound card. */
        public CompletableFuture<Void> started() {
            return started;
        }

        /** Completes when the clip ends, is stopped, or fails. */
        public CompletableFuture<Void> finished() {
            return finished;
        }

        /** Stops immediately (e.g. the player pressed Enter). */
        public void stop() {
            if (stopped.compareAndSet(false, true)) {
                SourceDataLine l = line;
                if (l != null) {
                    l.stop();
                    l.flush();
                }
                Process p = process;
                if (p != null) {
                    p.destroyForcibly();
                }
                complete();
            }
        }

        private void markStarted(Duration clip, ScheduledExecutorService scheduler) {
            started.complete(null);
            // Hard wall-clock cutoff: even if the network stalls, the clip never runs past 10.0 s.
            hardStop = scheduler.schedule(this::stop, clip.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void complete() {
            started.complete(null);
            finished.complete(null);
        }

        private void fail(Throwable t) {
            stopped.set(true);
            started.completeExceptionally(t);
            finished.completeExceptionally(t);
            Process p = process;
            if (p != null) {
                p.destroyForcibly();
            }
        }

        private void release() {
            ScheduledFuture<?> h = hardStop;
            if (h != null) {
                h.cancel(false);
            }
            SourceDataLine l = line;
            if (l != null) {
                l.stop();
                l.close();
            }
            Process p = process;
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
            finished.complete(null);
        }
    }

    // ============================================================== utilities

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    private static ProcessResult run(List<String> cmd, long timeoutSeconds) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).start();
        p.getOutputStream().close();
        CompletableFuture<String> out = readAllAsync(p.getInputStream());
        CompletableFuture<String> err = readAllAsync(p.getErrorStream());
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(cmd.get(0) + " timed out after " + timeoutSeconds + " s");
        }
        return new ProcessResult(p.exitValue(), out.join(), err.join());
    }

    private static CompletableFuture<String> readAllAsync(InputStream in) {
        CompletableFuture<String> f = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try (in) {
                f.complete(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                f.complete("");
            }
        });
        return f;
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().filter(l -> !l.isBlank()).findFirst().orElse("").trim();
    }

    /**
     * Env override → PATH → common Homebrew / system locations (IDEs launched from the macOS
     * Dock don't always inherit your shell's PATH).
     */
    static String locate(String binary, String envVar) {
        String override = System.getenv(envVar);
        if (override != null && !override.isBlank()) {
            return override;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exe = windows ? binary + ".exe" : binary;

        List<String> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            dirs.addAll(List.of(path.split(File.pathSeparator)));
        }
        String home = System.getProperty("user.home");
        dirs.addAll(List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin",
                home + "/.local/bin", home + "/miniconda3/bin", home + "/anaconda3/bin"));

        for (String dir : dirs) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, exe);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return binary; // let ProcessBuilder try; verifyDependencies() will explain if it fails
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
