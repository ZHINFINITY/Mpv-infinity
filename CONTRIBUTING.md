# Contributing to Mpv∞

Thank you for helping improve Mpv∞. This repository is an Android media-player project built with Kotlin, Jetpack Compose, MPV/libmpv, and AndroidX Media3.

## Before opening an issue

Search existing issues first. For bug reports, include the app version, device model, Android version, playback engine, media type, reproduction steps, and relevant in-app diagnostics. Do not include private cookies, account credentials, or personal media URLs.

## Pull requests

Keep pull requests focused on one change. Explain the user-facing result, identify affected playback engines or app areas, and include screenshots or logs when they clarify the change. Avoid committing generated build outputs, signing keys, local configuration, or unrelated formatting changes.

## Local checks

Use the Gradle wrapper and run the narrowest relevant checks before opening a pull request:

```bash
./gradlew :app:assembleStandardDebug
./gradlew :app:testStandardDebugUnitTest
```

Remote GitHub Actions builds are authoritative for release artifacts. Never commit APKs or signing material to the repository.

## Commit style

Use short, imperative commit subjects, such as `fix: preserve subtitle selection across engine switches` or `docs: clarify release variants`.

## License

Contributions intended for the unreleased next version are accepted under the [PolyForm Noncommercial License 1.0.0](LICENSE). By submitting a contribution, you agree that it may be incorporated into and distributed with that version under those terms. You retain ownership of your contribution; this notice does not assign your copyright or grant permission to use, publish, or relicense your contribution for purposes outside the PolyForm license. Any broader or commercial-purpose permission must be agreed to separately in writing by the relevant rights holder.

Do not submit third-party code, assets, or generated material as Mpv∞ Covered Code. If a contribution includes or depends on third-party material, identify its source and license and include all required notices; its original terms remain applicable. These contribution terms apply prospectively and do not change the terms for previously submitted contributions or previously published AGPL-3.0-or-later releases.
