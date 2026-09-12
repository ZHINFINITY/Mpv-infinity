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

By contributing, you agree that your contribution may be distributed under the repository's [AGPL-3.0-or-later license](LICENSE), subject to applicable third-party notices.
