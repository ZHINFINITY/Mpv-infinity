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

The covered source version and upcoming release offer custom [Mpv∞ Permission-Only Terms 1.0](LICENSE) for all project material ZHINFINITY owns or has express authority to offer under those exact terms, including unchanged carried-forward interpolation/feature code. It is not limited to new additions. See [LICENSE-BOUNDARY.md](LICENSE-BOUNDARY.md) for the owner-approved source boundary, preserved grants, third-party terms, ownership gaps, and binary-release gates. Merging this policy does not publish a tagged release.

Everyone may download, install, update, and use official, unmodified app releases from ZHINFINITY or an authorized distributor under LICENSE section 2.1 without asking for permission. The contribution restrictions below concern code reuse and publication, not ordinary app use; independently applicable rights under LICENSE sections 4 and 5 remain intact.

GitHub's built-in public-repository viewing and forking are expressly acceptable platform activities; no separate request is needed for those. For copying, modifying, reusing, or republishing Covered Material outside those platform rights or another applicable grant, obtain the written permission required by LICENSE section 2.2. Opening an issue or pull request is not an assignment of copyright or an automatic licence grant to the maintainer. Before accepting others' contributions, agree separately in writing on the rights to incorporate, modify, distribute, and (if intended) offer that exact contribution under these custom terms. Contributors retain ownership unless expressly agreed otherwise; existing grants and notices remain intact.

The written contribution instrument must expressly authorize offering the identified contribution under the exact custom terms, including any necessary sublicensing and patent permissions within that instrument's scope. Do not infer those rights from a pull request, an earlier licence, or the contributor guide itself.

There is no general contribution-workflow exception beyond the app-use grant and preserved legal, earlier-licence, third-party, and GitHub platform rights. If preparing/submitting a patch requires more rights, request specific written permission first. This guide is not that permission. A bug report describing behavior without reproducing covered code can be submitted without obtaining a code-reuse licence.

Identify every upstream source, contributor, and applicable licence, and preserve all required notices. Earlier contributions and published AGPL or PolyForm material retain their original terms. Permissions granted for an earlier licence are not presumed to authorize these different terms. Do not submit third-party code, assets, or generated material as exclusively ZHINFINITY-owned code.
