# ANVIL source notice

The ANVIL video filter and generated SPIR-V shader headers in this directory are vendored from [`NihilDigit/mpv-android-anvil`](https://github.com/NihilDigit/mpv-android-anvil), commit `1efc82739f6f7034cd63e7ee8b3ec5431df27467`.

The upstream `vf_anvil.c` carries its own copyright and permissive license notice at the top of the file; retain that notice in copies and substantial portions. The shader headers are the upstream generated assets used by the filter. `scripts/prepare-anvil-mpv.py` applies MPV registration, QAIRT-free compile stubs, and telemetry changes at build time; the vendored source remains unmodified.
