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

The proposed next source release uses custom [Mpv∞ Permission-Only Terms 1.0](LICENSE) for qualifying new ZHINFINITY-owned material only. See [LICENSE-BOUNDARY.md](LICENSE-BOUNDARY.md) for the exact prospective boundary, prior grants, third-party exclusions, and approval gates. This remains a draft until the owner approves the exact text and it is merged.

Everyone may download, install, update, and use official app releases under LICENSE section 2.1 without asking for permission. The contribution restrictions below concern code reuse and publication, not ordinary app use.

Do not copy, modify, use, or republish newly covered material for a contribution without the prior written permission required by those terms, unless an independently applicable right permits it. Opening an issue or pull request is not an assignment of copyright or an automatic licence grant to the maintainer. Before accepting third-party contributions for the next release, agree separately in writing on the rights to incorporate, modify, distribute, and (if intended) relicense the particular contribution. Contributors retain their ownership unless an express agreement says otherwise; their work is excluded from ZHINFINITY-owned coverage without documented authority.

There is deliberately no general contribution-workflow exception for newly covered material. Before preparing a patch that uses it beyond existing legal or GitHub platform rights, request written permission covering patch preparation and submission. This guide is not that permission. A bug report describing behavior without reproducing covered code can be submitted without obtaining a code-reuse licence.

Identify every upstream source, contributor, and applicable licence, and preserve all required notices. Earlier contributions and published AGPL or PolyForm material retain their original terms. Permissions granted for an earlier licence are not presumed to authorize these different terms. Do not submit third-party code, assets, or generated material as exclusively ZHINFINITY-owned code.
