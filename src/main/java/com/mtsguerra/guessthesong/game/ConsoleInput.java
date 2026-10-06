package com.mtsguerra.guessthesong.game;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Reads stdin on a dedicated daemon thread into a queue, so the game can wait for input
 * with a timeout (e.g. "press Enter to stop the clip") without ever blocking on System.in.
 */
public final class ConsoleInput implements AutoCloseable {

    /** Sentinel for end-of-input (compared by identity). */
    @SuppressWarnings("StringOperationCanBeSimplified")
    private static final String EOF = new String("\u0000EOF");

    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private volatile boolean closed;

    public ConsoleInput(InputStream in) {
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = br.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // treated as EOF
            } finally {
                lines.add(EOF);
            }
        }, "console-input");
        reader.setDaemon(true);
        reader.start();
    }

    /** Blocks until a line arrives; {@code null} on end of input (Ctrl+D). */
    public String readLine() throws InterruptedException {
        String line = lines.take();
        if (line == EOF) {
            lines.add(EOF); // keep returning null on later calls
            return null;
        }
        return line;
    }

    /** Waits up to {@code timeout}; empty if nothing was typed. {@code null} inside means EOF. */
    public Optional<String> poll(Duration timeout) throws InterruptedException {
        String line = lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (line == null) {
            return Optional.empty();
        }
        if (line == EOF) {
            lines.add(EOF);
            return Optional.of("/quit");
        }
        return Optional.of(line);
    }

    /** Throws away anything typed while it wasn't the player's turn. */
    public void discardPending() {
        lines.removeIf(l -> l != EOF);
    }

    @Override
    public void close() {
        closed = true;
    }
}
