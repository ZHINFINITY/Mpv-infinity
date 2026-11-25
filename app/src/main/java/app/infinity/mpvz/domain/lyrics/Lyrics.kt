/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package app.infinity.mpvz.domain.lyrics

/**
 * Online lyrics databases the repository can ask, in default priority order.
 *
 * Declaration order is the priority inside one quality tier. Every provider is
 * contacted together; actual word-timed lyrics beat line-timed lyrics, which
 * beat plain text. When two providers return the same timing quality, the one
 * declared first wins. The picker uses this order too.
 *
 * [wordSynced] describes provider capability for UI and diagnostics. Automatic
 * selection inspects the returned lyrics because a capable provider can still
 * return a line-synced fallback for one particular track.
 */
enum class LyricsProvider(
  val label: String,
  val detail: String,
  val wordSynced: Boolean,
) {
  BINI_LYRICS(
    label = "BiniLyrics",
    detail = "Apple Music timings, matched against the recording itself",
    wordSynced = true,
  ),
  BETTER_LYRICS(
    label = "BetterLyrics",
    detail = "Apple Music timings, word by word",
    wordSynced = true,
  ),
  BETTER_LYRICS_PORTATO(
    label = "BetterLyrics Portato",
    detail = "QQ Music karaoke timings through BetterLyrics",
    wordSynced = true,
  ),
  PAXSENIX(
    label = "PaxSenix",
    detail = "Apple Music timings through a keyless proxy",
    wordSynced = true,
  ),
  PAXSENIX_SPOTIFY(
    label = "PaxSenix: Spotify",
    detail = "Spotify lyrics with a PaxSenix fallback; API key required",
    wordSynced = false,
  ),
  PAXSENIX_MUSIXMATCH(
    label = "PaxSenix: Musixmatch",
    detail = "Musixmatch timings with a PaxSenix fallback; API key required",
    wordSynced = true,
  ),
  LYRICS_PLUS(
    label = "LyricsPlus",
    detail = "Syllable by syllable, served from community mirrors",
    wordSynced = true,
  ),
  SIMP_MUSIC(
    label = "SimpMusic",
    detail = "Matched on the playing video, so never the wrong edit",
    wordSynced = true,
  ),
  UNISON(
    label = "Unison",
    detail = "Listener-contributed, so it carries what nobody licensed",
    wordSynced = true,
  ),
  YOUTUBE_TRANSCRIPT(
    label = "YouTube captions",
    detail = "Timed captions of the playing video",
    wordSynced = false,
  ),
  YOUTUBE_MUSIC(
    label = "YouTube Music",
    detail = "Plain lyrics from the video's Lyrics tab",
    wordSynced = false,
  ),
  MEGALOBIZ(
    label = "Megalobiz",
    detail = "Community-made, whole-line LRC",
    wordSynced = false,
  ),
  KUGOU(
    label = "KuGou",
    detail = "Whole lines, strong outside the English catalogue",
    wordSynced = false,
  ),
  LRCLIB(
    label = "LRCLIB",
    detail = "Whole lines only, and usually up",
    wordSynced = false,
  ),
  MUSIXMATCH(
    label = "Musixmatch",
    detail = "Word timing from the largest catalogue there is",
    wordSynced = false,
  ),
  GENIUS(
    label = "Genius",
    detail = "Plain text last resort, huge web catalogue",
    wordSynced = false,
  ),
  ;

  companion object {
    /**
     * Contacted only when nothing above [GENIUS] has answered: it is a plain
     * web scrape, so it stays out of the way of the sources that can be asked
     * politely.
     */
    val LAZY_PROVIDERS: Set<LyricsProvider> = setOf(GENIUS)
  }
}
