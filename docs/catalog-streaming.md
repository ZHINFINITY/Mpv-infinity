# Stream tab and playback workflow

The Stream tab now follows the NuvioMobile discovery and playback design. It loads rails, search results, title details, and episode metadata only from catalog add-ons installed in **Global Settings → Network → Media Servers → Stream Catalogs**. Direct stream resolution uses individually enabled Nuvio JavaScript providers configured in **Nuvio JavaScript Providers** on the same page.

There is no `CloudStreamResolver`, Stremio `/stream/...` route, torrent selection, resolver token, or torrent chooser in this flow. Stream accepts only direct HTTPS media URLs returned by Nuvio `getStreams` and opens them in `PlayerActivity` with provider-supplied request headers.

Manual magnet/torrent playback remains independent in the Network tab: users paste a magnet or torrent link there and use the existing Network playback workflow.

For implementation details, configuration, current limitations, and verification, see [NUVIO_STREAM_WORKFLOW.md](NUVIO_STREAM_WORKFLOW.md).
