# Historical Android MediaCodec → Vulkan AImageReader adapter

This directory retains an earlier local AImageReader/Vulkan conversion adapter and editable shader sources. The files preserve their original LGPL-2.1 attribution and `LICENSE.LGPL` text; they are kept for provenance/history and are not copied or compiled into the current MPV runtime.

The experimental MPV runtime now uses the native Android AImageReader/Vulkan mapper in [FongMi/mpv commit `95ec8e929f8ad2f97c8415c486971493a0b408df`](https://github.com/FongMi/mpv/tree/95ec8e929f8ad2f97c8415c486971493a0b408df/video/out/hwdec). `scripts/prepare-mpvflow.py` validates that pinned upstream source and applies only Android build adaptations; it does not copy this directory or apply the former mapper patch. Native Media3 is separately restored to its pre-FongMi MPVFlow implementation at `8852ac88cd5831b2e7cdb1a19e05bf9e458db63f`.

Retain this note and file-level notices while the historical adapter sources remain in the repository. The experimental branch is unmerged and unreleased.
