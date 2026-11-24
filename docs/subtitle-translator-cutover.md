# Subtitle translation cutover — phase notes

## Goal
Replace the old per-cue live subtitle translator with the single whole-track
`SubtitleTranslator` (free Google dict-chrome-ex endpoint, cue-by-cue rewrite of
the selected embedded track), with no other app's names in code or settings.

## Phases (each left the app building and testable)
1. `47c2af49` — whole-track translation: selected-track targeting, always-visible
   language chips from AI settings, bounded concurrency (4 at a time, retries with
   backoff), per-cue toggle row removed from the sheet.
2. `c6c7aa43` — old per-cue pipeline deleted: cue collector in
   `PlayerActivity`, `translateEmbeddedSubtitleCue` + per-cue state in
   `PlayerViewModel`, translated overlay in `PlayerControls`,
   overlay gesture special-casing in `GestureHandler`, the
   `playerSubtitleTranslationEnabled` preference, and the
   `TranslatedSubtitleText` composable. Whole-track path is now the only
   translate path.

## Behaviour preserved exactly
- Speech-to-text "Generate subtitles" flow untouched.
- External (sideloaded .srt/.ass) subtitle per-cue translate via
  `onTranslateSubtitle` untouched.
- Track selection, subtitle positioning and styling untouched (overlay-only
  code removed; mpv renders originals as before).
- Two contract tests that pinned deleted code were updated to the new state:
  the PlayerControls hash pin and the PlayerActivity cue-wiring assertion.

## Verification
- `./gradlew :app:testStandardDebugUnitTest` → BUILD SUCCESSFUL (exit 0) at `c6c7aa43`.
- `python3 scripts/tests/localization_resources_test.py` → OK.
- Repo-wide search: no "Zangetsu" or other player-app names in code/strings;
  only real service names remain (Google, DeepL, OpenAI, Jellyfin).
- Test APK: GitHub Actions run 45239627422 (arm64-v8a debug).

## Rollback
Single-commit revert of `c6c7aa43` restores the per-cue translator and the old
preference (its stored value was only ever read; removal loses no data).
Revert of `47c2af49` on top returns to the pre-cutover icon set.
