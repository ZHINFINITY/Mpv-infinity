# Nuvio Stream integration

## Goal and reference

The Stream tab is being ported from the NuvioMobile workflow, not from a Stremio-resolver workflow. Reference repository: [NuvioMobile](https://github.com/NuvioMedia/NuvioMobile), inspected at its `cmp-rewrite` snapshot (`a3bee1ac06446083b98d454bbebb93356b0eae64`). The Mpv-infinity implementation base remains the user-specified commit `8b2c586c8cc584f7113501eaf071ccfd9271e789` on `feature/nuvio-stream-direct-https`.

The reported example provider is [yoruix/nuvio-providers](https://github.com/yoruix/nuvio-providers), with manifest at `https://raw.githubusercontent.com/yoruix/nuvio-providers/main/manifest.json`. Its current manifest reports 29 providers; a read-only check on 2026-09-26 found all 29 referenced JavaScript files reachable. NuvioMobile is GPL-3.0; its license is included in `THIRD_PARTY_LICENSES/NuvioMobile-GPL-3.0.txt` alongside the target project's AGPL-3.0 license.

## Protocol distinction — important

NuvioMobile uses **two separate kinds of extensions**:

1. **Catalog/metadata add-ons** expose the Stremio manifest/catalog/meta protocol and populate discovery shelves, search results, and TV episode metadata.
2. **Nuvio scraper/plugin repositories** expose a `manifest.json` containing individual JavaScript scraper providers. Each provider's `getStreams(tmdbId, mediaType, season, episode)` function fetches candidate sources. This is what `yoruix/nuvio-providers` is. It is **not** a Stremio `/stream/...` endpoint.

In the inspected NuvioMobile source snapshot, catalog shelves are built from installed catalog add-ons (or user-created collections); its TMDB key is a build-configured fallback for TMDB-backed features, not a catalog generator. Mpv-infinity deliberately adds a built-in, keyless public discovery fallback at `https://v3-cinemeta.strem.io/manifest.json` so first-run Stream always has searchable movie/series rails, including when the user has installed only a JavaScript provider repository. This is not a dependency on the failing RPDB Cinemeta URL previously present in the user's settings; legacy RPDB/Cinemeta and Kitsu entries are migrated out, while the official v3 endpoint is retained as an explicitly labeled, non-removable built-in fallback. Users can still install additional Nuvio-compatible catalog add-ons.

Mpv-infinity keeps those paths separate. The built-in discovery catalog and optional catalog add-ons populate rails, search, and metadata. JavaScript provider repositories are installed in **Settings → Network → Media Servers → Nuvio JavaScript Providers**; that screen groups all providers beneath the repository name and expands to show supported media types, enabled state, version, and (where declared) its `onSettings()` controls. Previously saved RPDB Cinemeta and Kitsu sources are ignored. A repository mistakenly entered under the former catalog-source mechanism is migrated to JavaScript provider storage when it is a valid Nuvio manifest.

## Stream workflow

- Home uses an immersive Nuvio-style hero, floating search/settings controls, artwork-led horizontal catalog shelves and a clear error/setup state; search is an editable native field, debounced and submitted to catalog feeds advertising search support. The official v3 Cinemeta feed provides the no-key starter rails.
- Selecting a title replaces the home content with a full-page details view. Metadata and episodes are hydrated from installed add-ons/TMDB; seasons use artwork cards and episodes use a landscape rail with per-episode direct-source resolution.
- Opening a movie or a specific episode calls enabled JavaScript providers with a numeric TMDB ID. IMDb IDs are mapped through TMDB `/find` when the user's key is configured; otherwise the ID is resolved through Wyzie search using title/year/type. Lookup runs off the Android main thread.
- Each configured provider's JavaScript is executed by the Nuvio QuickJS runtime, including Nuvio's JS shims and host bridges for fetch, URL, DOM/HTML selection, crypto and WASM. Provider-specific `onSettings()` values and the TMDB API key are stored encrypted and supplied to the runtime.
- Only direct `https://` media URLs are displayed as playable. `magnet:`, `infoHash`, torrent sources, external-player links, non-HTTPS links, and other non-direct results are excluded from Stream. Request headers returned by a provider are forwarded to the player.
- Manual torrent streaming remains a Network-only workflow: users paste copied magnets into Network. The Stream tab does not route through TorrentSelectionActivity.

## Limitations to surface to users

- **Some provider scripts require a TMDB API key** when they make their own TMDB API requests. The Nuvio provider settings card stores the key encrypted, confirms the durable save, and supplies it to scrapers; without a key, those specific providers may fail while other providers continue to work. No third-party API key is bundled by this port.
- Provider compatibility depends on each remote JS scraper's current site/API behavior. The runtime reports provider failures individually; a repository installing successfully does not guarantee that every source site is currently reachable.
- Nuvio JS scraper repositories provide stream sources, not home catalog rails or episode metadata. The built-in v3 catalog supplies default discovery; separately installed catalog/metadata add-ons can extend it.
- Stream results are deliberately direct HTTPS-only; torrent-only providers will not appear as playable sources in Stream.
- NuvioMobile's current WASM bridge is a stub rather than a WebAssembly engine. A scraper that actually requires WASM or unavailable Node/native modules may fail. The Yoru manifest's 29 provider scripts were statically checked for WebAssembly/native Node API usage; none required WebAssembly or Node built-ins, while its `process.env` use is covered by the Android shim.

## Source map

- `app/src/main/java/app/infinity/mpvz/catalog/nuvio/` — provider manifest parsing, encrypted repository/provider configuration, code cache, TMDB matching, and stream aggregation.
- `app/src/main/java/app/infinity/mpvz/catalog/nuvio/runtime/` — QuickJS executor and Android worker; the `js/`, `dom/`, `crypto/`, `network/`, `host/`, and `wasm/` bridges are ported/adapted from NuvioMobile.
- `app/src/main/java/app/infinity/mpvz/ui/preferences/NuvioAddonsPreferences.kt` — global provider repository and individual provider UI, provider settings controls, and API-key entry.
- `app/src/main/java/app/infinity/mpvz/catalog/CatalogViewModel.kt` — add-on-driven home/search/detail metadata state and calls into Nuvio providers for source lookup.
- `app/src/main/java/app/infinity/mpvz/ui/browser/catalog/StreamScreen.kt` and `CatalogDetailsPage.kt` — Nuvio-style shelves, search, full-page details, episode rail, direct stream rows, and player handoff. The former `CatalogScreen.kt` and generic grid-card implementation have been removed.
- `app/src/main/java/app/infinity/mpvz/ui/preferences/CatalogAddonsPreferences.kt` — Global Settings catalog source installation and per-source controls.
- `app/src/main/java/app/infinity/mpvz/ui/browser/networkstreaming/NetworkStreamingScreen.kt` — existing manual magnet URL workflow; intentionally not repurposed for catalog streams.
- `app/libs/quickjs-kt-android-1.0.5-nuvio.aar` and `THIRD_PARTY_LICENSES/` — Android QuickJS engine and source/license attribution.

## Branch and validation constraints

- Work branch: `feature/nuvio-stream-direct-https`, rooted at the requested base commit above.
- Do not create another branch or build an APK locally. This sandbox has no Android SDK.
- Validate source statically in the sandbox, push fixes to the same feature branch, and use `.github/workflows/music-jellyfin-debug.yml` to build the arm64-v8a Standard Debug APK remotely.
- Use only the exact feature-branch workflow run for artifact verification; the protected `main` branch is not a target.
