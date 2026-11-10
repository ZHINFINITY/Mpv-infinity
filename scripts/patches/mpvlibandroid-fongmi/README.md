# MPV-RX FongMi libbluray patch set

These patches are copied byte-for-byte from the FongMi build branch of [Riteshp2001/mpvlibAndroid](https://github.com/Riteshp2001/mpvlibAndroid/tree/d04217abe8e72e4a099269f045855bf69b87d53b), immutable commit `d04217abe8e72e4a099269f045855bf69b87d53b` (the `mpvlib 1.1.8` preparation commit). MPV-RX declares the matching `mpvlib-fongmi` AAR version `1.1.8`. The upstream mpvlibAndroid wrapper/build-helper MIT notice is retained at `app/src/main/assets/mpv/licenses/mpvlibAndroid-LICENSE-MIT`.

The target is the unmodified VideoLAN libbluray 1.4.1 source archive, SHA-256 `76b5dc40097f28dca4ebb009c98ed51321b2927453f75cc72cf74acd09b9f449`. The patches are applied in the same order as the upstream FongMi AAR builder, with zero fuzz and forward/reverse dry-run checks:

1. `libbluray-1.4.1-still-eof.patch` — SHA-256 `cc0a15ee88b1ead665dadbc3ac363c2686e5184fc1e04a76713665cad57c0fc7`
2. `libbluray-1.4.1-checked-seek.patch` — SHA-256 `1055d99f0940b3077e9f7171a220f242396e6bf5196415f3fc987b316d51447c`
3. `libbluray-1.4.1-menu-info.patch` — SHA-256 `17a1b88c72c3b467ba6ac8d9419f003c93f694733b9214deee13bad5ebfedb5c`
4. `libbluray-1.4.1-bdj-pointer.patch` — SHA-256 `232ce1e634335af5d69a705745a57ea5cd5da8d7160b9441b5ba154e7b3dec85`

The checked-seek patch provides `bd_seek_time_checked`; the menu-info patch provides `bd_get_titles_with_menu_info`, which reports interactive graphics before playlist filtering. The still-EOF and BD-J pointer patches are companion fixes from the same FongMi AAR build. `patch-libbluray.sh` rejects a mismatched source tree and verifies both required APIs after patching.

The patches are build inputs for the experimental arm64 FongMi MPV runtime only. They do not change the checked-in standard AARs or Native Media3 interpolation. The patch hunks modify LGPL-licensed libbluray; retain its original LGPL-2.1 notices and corresponding source. The mpvlibAndroid MIT notice covers its upstream wrapper/build-helper source and does not relicense libbluray.
