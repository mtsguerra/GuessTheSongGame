package com.mtsguerra.guessthesong.spotify;

import java.io.IOException;

/**
 * Supplies a Spotify Web API bearer token. Implementations cache and refresh as needed.
 */
public interface TokenProvider {

    /** A currently valid access token. */
    String accessToken() throws IOException, InterruptedException;

    /** Forget the cached token (called after a 401) so the next call fetches a fresh one. */
    void invalidate();

    /** Human-readable name, e.g. "Client Credentials". */
    String description();
}
