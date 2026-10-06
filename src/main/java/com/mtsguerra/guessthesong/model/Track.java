package com.mtsguerra.guessthesong.model;

import java.util.List;
import java.util.Objects;

/**
 * A playable track extracted from a Spotify playlist.
 *
 * @param spotifyId       Spotify track ID
 * @param title           cleaned title (no "- Remastered 2011", "(feat. X)", ...)
 * @param rawTitle        title exactly as Spotify returned it
 * @param mainArtist      primary artist (first entry in Spotify's artist array)
 * @param featuredArtists secondary artists (may be empty, never null)
 * @param durationMs      track length according to Spotify
 */
public record Track(
        String spotifyId,
        String title,
        String rawTitle,
        String mainArtist,
        List<String> featuredArtists,
        long durationMs) {

    public Track {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(mainArtist, "mainArtist");
        rawTitle = rawTitle == null ? title : rawTitle;
        featuredArtists = featuredArtists == null ? List.of() : List.copyOf(featuredArtists);
    }

    public boolean hasFeatures() {
        return !featuredArtists.isEmpty();
    }

    public String featuredDisplay() {
        return hasFeatures() ? String.join(", ", featuredArtists) : "none";
    }

    /** "Title — Artist (feat. A, B)" */
    public String displayName() {
        String base = title + " — " + mainArtist;
        return hasFeatures() ? base + " (feat. " + String.join(", ", featuredArtists) + ")" : base;
    }
}
