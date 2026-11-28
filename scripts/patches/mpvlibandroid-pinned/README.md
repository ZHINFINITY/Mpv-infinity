# MPV-RX Pinned libbluray patch set

These patches are based on the Pinned build branch of [Riteshp2001/mpvlibAndroid](https://github.com/Riteshp2001/mpvlibAndroid/tree/d04217abe8e72e4a099269f045855bf69b87d53b), immutable commit `d04217abe8e72e4a099269f045855bf69b87d53b` (the `mpvlib 1.1.8` preparation commit). The checked-seek patch has two locally corrected unified-diff hunk-length headers; its intended libbluray source changes are unchanged. MPV-RX declares the matching `mpvlib-pinned` AAR version `1.1.8`. The upstream mpvlibAndroid wrapper/build-helper MIT notice is retained at `app/src/main/assets/mpv/licenses/mpvlibAndroid-LICENSE-MIT`.

The target is the unmodified VideoLAN libbluray 1.4.1 source archive, SHA-256 `76b5dc40097f28dca4ebb009c98ed51321b2927453f75cc72cf74acd09b9f449`. The patches are applied in the same order as the upstream Pinned AAR builder, with zero fuzz and forward/reverse dry-run checks:

1. `libbluray-1.4.1-still-eof.patch` — SHA-256 `cc0a15ee88b1ead665dadbc3ac363c2686e5184fc1e04a76713665cad57c0fc7`
2. `libbluray-1.4.1-checked-seek.patch` — SHA-256 `58ef2e5649b2bd592352ac5823c226b534de43dcadbf346f4d472bb3aa16926b` (hunk-length metadata correction only)
3. `libbluray-1.4.1-menu-info.patch` — SHA-256 `17a1b88c72c3b467ba6ac8d9419f003c93f694733b9214deee13bad5ebfedb5c`
4. `libbluray-1.4.1-bdj-pointer.patch` — SHA-256 `232ce1e634335af5d69a705745a57ea5cd5da8d7160b9441b5ba154e7b3dec85`

The checked-seek patch provides `bd_seek_time_checked`; the menu-info patch provides `bd_get_titles_with_menu_info`, which reports interactive graphics before playlist filtering. The still-EOF and BD-J pointer patches are companion fixes from the same Pinned AAR build. `patch-libbluray.sh` rejects a mismatched source tree and verifies both required APIs after patching.

The patches are build inputs for the experimental arm64 Pinned MPV runtime only. They do not change the checked-in standard AARs or Native Media3 interpolation. The patch hunks modify LGPL-licensed libbluray; retain its original LGPL-2.1 notices and corresponding source. The mpvlibAndroid MIT notice covers its upstream wrapper/build-helper source and does not relicense libbluray.
