package com.mtsguerra.guessthesong.model;

/**
 * Outcome of a single round: what the player typed, what was correct, and the points earned.
 */
public record RoundResult(
        int round,
        Track track,
        String titleGuess,
        String mainArtistGuess,
        String featuredGuess,
        boolean titleCorrect,
        boolean mainArtistCorrect,
        boolean featuredCorrect,
        int points) {
}
