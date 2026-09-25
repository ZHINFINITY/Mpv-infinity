# Nuvio Stream integration

## Goal and reference

The Stream tab is being ported from the NuvioMobile workflow, not from a Stremio-resolver workflow. Reference repository: [NuvioMobile](https://github.com/NuvioMedia/NuvioMobile), inspected at its `cmp-rewrite` snapshot (`a3bee1ac06446083b98d454bbebb93356b0eae64`). The Mpv-infinity implementation base remains the user-specified commit `8b2c586c8cc584f7113501eaf071ccfd9271e789` on `feature/nuvio-stream-direct-https`.

The reported example provider is [yoruix/nuvio-providers](https://github.com/yoruix/nuvio-providers), with manifest at `https://raw.githubusercontent.com/yoruix/nuvio-providers/main/manifest.json`. Its current manifest reports 29 providers; a read-only check on 2026-09-26 found all 29 referenced JavaScript files reachable. NuvioMobile is GPL-3.0; its license is included in `THIRD_PARTY_LICENSES/NuvioMobile-GPL-3.0.txt` alongside the target project's AGPL-3.0 license.

## Protocol distinction — important

NuvioMobile uses **two separate kinds of extensions**:

1. **Catalog/metadata add-ons** expose the Stremio manifest/catalog/meta protocol and populate discovery shelves, search results, and TV episode metadata.
2. **Nuvio scraper/plugin repositories** expose a `manifest.json` containing individual JavaScript scraper providers. Each provider's `getStreams(tmdbId, mediaType, season, episode)` function fetches candidate sources. This is what `yoruix/nuvio-providers` is. It is **not** a Stremio `/stream/...` endpoint.

Mpv-infinity keeps those paths separate. Existing catalog/metadata providers populate the home rails and title metadata. **Settings → Network → Media Servers → Nuvio Add-ons** manages JavaScript provider repositories and shows every individual provider, its supported media types, enabled state, version, and (where declared) its `onSettings()` controls. A repository mistakenly entered under the old Stremio catalog-source mechanism is detected, imported into the provider repository store when it is a valid Nuvio manifest, and removed from the Stremio catalog/stream list.

## Stream workflow

- Home uses an artwork-led hero and horizontal catalog shelves; search is an editable native field and submits/debounces catalog searches.
- Selecting a title replaces the home content with a full-page details view. The page shows metadata and, for series, horizontal season chips and landscape episode cards.
- Opening a movie or a specific episode calls enabled providers for that media type with a numeric TMDB ID. The ID is taken from metadata when available or resolved through the existing Wyzie TMDB search endpoint using title/year/type.
- Each configured provider's JavaScript is executed by the Nuvio QuickJS runtime, including Nuvio's JS shims and host bridges for fetch, URL, DOM/HTML selection, crypto and WASM. Provider-specific `onSettings()` values and the TMDB API key are stored encrypted and supplied to the runtime.
- Only direct `https://` media URLs are displayed as playable. `magnet:`, `infoHash`, torrent sources, external-player links, non-HTTPS links, and other non-direct results are excluded from Stream. Request headers returned by a provider are forwarded to the player.
- Manual torrent streaming remains a Network-only workflow: users paste copied magnets into Network. The Stream tab does not route through TorrentSelectionActivity.

## Limitations to surface to users

- **Some provider scripts require a TMDB API key** when they make their own TMDB API requests. The Nuvio provider settings card now has an encrypted, device-local key field; without a key, those specific providers may fail while other providers continue to work. No third-party API key is bundled by this port.
- Provider compatibility depends on each remote JS scraper's current site/API behavior. The runtime reports provider failures individually; a repository installing successfully does not guarantee that every source site is currently reachable.
- Nuvio JS scraper repositories provide stream sources, not home catalog rails or episode metadata. Those continue to come from catalog/metadata providers, by design.
- Stream results are deliberately direct HTTPS-only; torrent-only providers will not appear as playable sources in Stream.
- NuvioMobile's current WASM bridge is a stub rather than a WebAssembly engine. A scraper that actually requires WASM or unavailable Node/native modules may fail. The Yoru manifest's 29 provider scripts were statically checked for WebAssembly/native Node API usage; none required WebAssembly or Node built-ins, while its `process.env` use is covered by the Android shim.

## Source map

- `app/src/main/java/app/infinity/mpvz/catalog/nuvio/` — provider manifest parsing, encrypted repository/provider configuration, code cache, TMDB matching, and stream aggregation.
- `app/src/main/java/app/infinity/mpvz/catalog/nuvio/runtime/` — QuickJS executor and Android worker; the `js/`, `dom/`, `crypto/`, `network/`, `host/`, and `wasm/` bridges are ported/adapted from NuvioMobile.
- `app/src/main/java/app/infinity/mpvz/ui/preferences/NuvioAddonsPreferences.kt` — global provider repository and individual provider UI, provider settings controls, and API-key entry.
- `app/src/main/java/app/infinity/mpvz/catalog/CatalogViewModel.kt` — home catalog/search/detail metadata state and calls into Nuvio providers for source lookup.
- `app/src/main/java/app/infinity/mpvz/ui/browser/catalog/StreamScreen.kt` and `CatalogDetailsSheet.kt` — Nuvio-style shelves, search, full-page details, episode rail, direct stream rows, and player handoff.
- `app/src/main/java/app/infinity/mpvz/ui/browser/networkstreaming/NetworkStreamingScreen.kt` — existing manual magnet URL workflow; intentionally not repurposed for catalog streams.
- `app/libs/quickjs-kt-android-1.0.5-nuvio.aar` and `THIRD_PARTY_LICENSES/` — Android QuickJS engine and source/license attribution.

## Branch and validation constraints

- Work branch: `feature/nuvio-stream-direct-https`, rooted at the requested base commit above.
- Do not create another branch or build an APK locally. This sandbox has no Android SDK.
- Validate source statically in the sandbox, push fixes to the same feature branch, and use `.github/workflows/music-jellyfin-debug.yml` to build the arm64-v8a Standard Debug APK remotely.
- Use only the exact feature-branch workflow run for artifact verification; the protected `main` branch is not a target.
