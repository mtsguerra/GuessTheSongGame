# Guess the Song 🎵

A 10-round console game: paste a Spotify playlist, hear a random 10-second clip of each song
(fetched from YouTube lyric / official-audio uploads), and guess the title, main artist and
featured artists.

## Requirements

- Java 21, Maven
- [yt-dlp](https://github.com/yt-dlp/yt-dlp) + [Deno](https://deno.com) (yt-dlp uses it for YouTube) and [ffmpeg](https://ffmpeg.org)
  ```bash
  brew install yt-dlp deno ffmpeg      # macOS
  ```
- A Spotify app from <https://developer.spotify.com/dashboard> with redirect URI
  `http://127.0.0.1:8888/callback`

## Configuration

| Env var | Required | Default |
|---|---|---|
| `SPOTIFY_CLIENT_ID` | yes | |
| `SPOTIFY_CLIENT_SECRET` | yes (unless `SPOTIFY_AUTH=pkce`) | |
| `SPOTIFY_AUTH` | no | `auto` → Client Credentials, then browser login if Spotify hides the tracks. Also `client` or `pkce` |
| `SPOTIFY_REDIRECT_URI` | no | `http://127.0.0.1:8888/callback` |
| `YTDLP_PATH`, `FFMPEG_PATH` | no | found on `PATH` / Homebrew |

## Run

```bash
mvn package
java -jar target/guess-the-song.jar "https://open.spotify.com/playlist/<id>"
```

Or in IntelliJ: run `Main` with the env vars set in the Run Configuration.

## Scoring

| | Points |
|---|---|
| Title | 2 |
| Main artist | 2 |
| Featured artist(s), auto-awarded when there are none | 1 |
| **Per round / game** | **5 / 50** |

Matching ignores case, accents and punctuation, and accepts small typos
(Levenshtein distance ≤ 2, or ≥ 85 % similarity; shorter answers get less slack).

## Spotify limitation (2026)

Since Spotify's February 2026 Development Mode changes, apps only receive the tracks of playlists
the logged-in user **owns or collaborates on**. Spotify-made editorial playlists are not
available at all. If a playlist comes back empty, the game offers a one-time browser login.
For someone else's playlist, copy its songs into a playlist you own first.

## Layout

```
spotify/  SpotifyService, TitleCleaner, TokenProvider (+ ClientCredentials, PKCE)
audio/    YouTubeAudioStreamer  (yt-dlp → ffmpeg → SourceDataLine)
engine/   TextNormalizer, Levenshtein, ScoringEngine
game/     GameSession, ConsoleInput
model/    Track, RoundResult
```
