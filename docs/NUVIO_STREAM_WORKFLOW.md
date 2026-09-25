# Nuvio-style Stream workflow

## Purpose and scope

This feature replaces the former **Stream**-tab playback path with a Nuvio-inspired catalog and direct-stream workflow. The implementation is based on the user-specified Mpv-infinity commit `8b2c586c8cc584f7113501eaf071ccfd9271e789` and the NuvioMobile default-branch snapshot inspected for this task (`cmp-rewrite`, commit `a3bee1ac06446083b98d454bbebb93356b0eae64`). No other Mpv-infinity branch or older source snapshot was used as the implementation base.

Reference project: [NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)

## User-visible behavior

- **Stream** is for discovering movies and series from enabled Stremio-compatible add-ons, opening title details, selecting a season/episode, fetching streams, and playing a direct HTTPS video URL in Mpv-infinity's player.
- The home page uses an artwork-led hero carousel and horizontal poster/catalog shelves. Cards open in-app details; they do not open the torrent chooser.
- Add-on catalog, metadata, and stream resources are requested using the manifest-advertised Stremio protocol. Per-title metadata and video/episode metadata are fetched before playback selection when available.
- A stream is offered from Stream only when the add-on returns a playable `https://` URL. `magnet:`, torrent-specific data, external links, HTTP-only URLs, and non-video links are not routed to the player from this screen.
- Add-on `behaviorHints.proxyHeaders.request`, when supplied, are forwarded as player HTTP headers.
- Add-ons can be added, enabled/disabled, and removed in **Settings → Network → Media Servers → Nuvio Add-ons**. A host, add-on base URL, or manifest URL is normalized to a `manifest.json` URL; query configuration is retained.
- **Manual torrent streaming remains in Network**: users may paste a copied magnet link into Network. Torrent selection and file picking remain confined to actual torrent/manual-source launches. The torrent picker no longer accepts a catalog item as a substitute for a missing torrent source.

## Implementation map

- `app/src/main/java/app/infinity/mpvz/ui/browser/catalog/StreamScreen.kt` — Stream home/catalog rails, hero, search/browse navigation, details-sheet connection, and direct HTTPS player dispatch.
- `app/src/main/java/app/infinity/mpvz/ui/browser/catalog/CatalogGridItem.kt` — poster-first shelf card with title and release year below the art.
- `app/src/main/java/app/infinity/mpvz/ui/browser/catalog/CatalogDetailsSheet.kt` — title details, metadata, season/episode selection, and direct HTTPS results.
- `app/src/main/java/app/infinity/mpvz/catalog/CatalogViewModel.kt` — screen state, add-on configuration refresh, metadata/episode loading, stream filtering, and playback handoff.
- `app/src/main/java/app/infinity/mpvz/catalog/CatalogRepository.kt` — manifest/catalog/metadata/stream URL handling, configurable add-on persistence, and HTTPS-only stream parsing.
- `app/src/main/java/app/infinity/mpvz/ui/preferences/MediaServersPreferencesScreen.kt` and `NuvioAddonsPreferences.kt` — global add-on management controls.
- `app/src/main/java/app/infinity/mpvz/ui/torrent/TorrentSelectionActivity.kt` — manual torrent-source picker only; no catalog resolver branch.
- `app/src/main/java/app/infinity/mpvz/ui/browser/networkstreaming/NetworkStreamingScreen.kt` — existing Network magnet/URL workflow intentionally left unchanged.

## Protocol and safety boundary

Each add-on is represented by its manifest URL. Catalog and metadata resource requests use the configured add-on base path and preserve URL query configuration. Stream requests use `/stream/{type}/{encoded-id}.json`; series episodes use the provider ID plus season/episode in the Stremio resource ID. The parser exposes only a direct HTTPS `url` as playable. It deliberately does not convert `infoHash` or `magnet` values into torrents and does not open an add-on's `externalUrl` from Stream.

The player launch receives the direct URL and any add-on request headers along with title/artwork/description metadata. Manual Network magnet links still go through the existing torrent engine and picker path, not the Stream resolver.

## Remote build and branch

- Work branch: `feature/nuvio-stream-direct-https`.
- Root/base: the user-supplied commit above.
- Do not build an APK locally. The Kotlin-only compile was attempted but could not configure because this sandbox has no Android SDK (`ANDROID_HOME`/`ANDROID_SDK_ROOT` unset and no SDK directory found).
- Use the repository's `.github/workflows/music-jellyfin-debug.yml` on the feature branch via `workflow_dispatch`. It runs `:app:assembleStandardDebug -PtargetAbi=arm64-v8a` remotely and is configured to upload `app-standard-arm64-v8a-debug.apk` as a private CI artifact.
- Do not target protected `main`, do not change the user's selected feature branch, and do not substitute an older base commit.

## Validation status

Source formatting/whitespace checks were clean at the initial validation point. Remote Android compilation and the requested arm64-v8a debug APK remain the authoritative validation because a local Android SDK is unavailable. After dispatch, inspect the exact workflow run on this feature branch; correct compile failures on this same branch and rerun the remote workflow until it succeeds.
