# Native Catalog and Streaming Resolver

The Catalog tab is registered in `MainScreen` and is intentionally additive to the existing browser and player flows. `CatalogViewModel` owns debounced search, trending loading, resolver state, and the one-shot resolved URL event. `TmdbCatalogRepository` uses Retrofit with the kotlinx.serialization converter; posters use Coil; `CatalogSettings` stores the TMDB key, resolver URL, and bearer token in `EncryptedSharedPreferences`.

The app does not embed a public torrent index or provider credentials. `CloudStreamResolver` is the in-app link-layer adapter for a user-controlled HTTPS resolver/debrid gateway. This keeps provider-specific scraping and debrid API credentials outside the APK while retaining the native Stremio-style UX. The gateway receives a JSON request:

```json
{"tmdbId":123,"imdbId":null,"title":"Example","type":"MOVIE"}
```

at `POST {resolverBaseUrl}/resolve` with `Authorization: Bearer {resolverToken}` and returns:

```json
{"url":"https://provider.example/short-lived-video.mp4","mimeType":"video/mp4","headers":{}}
```

Only HTTP(S) URLs are accepted. On success, the URL is sent through the existing `PlayerActivity` `ACTION_VIEW` contract, which preserves the repository's native mpv/Media3 playback path.

## Verification

1. In the Catalog tab, open settings and enter a TMDB API key plus the HTTPS resolver base URL and token.
2. Confirm the blank-query state loads trending movies and TV items.
3. Enter at least three characters and verify the request is debounced and the poster grid updates.
4. Tap a result and confirm the resolver receives the stable TMDB ID and title.
5. Confirm the resolver returns a short-lived HTTP(S) media URL and that `PlayerActivity` opens it.
6. Push to `main`; `.github/workflows/build.yml` is configured to run on pushes to the repository's build branches. No local APK build is required for this change.

A production resolver should validate TMDB IDs, enforce provider terms and regional restrictions, issue short-lived URLs, and avoid returning credentials or torrent metadata to the client.
