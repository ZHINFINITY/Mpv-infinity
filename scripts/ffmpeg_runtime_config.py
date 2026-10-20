#!/usr/bin/env python3
"""Interpret effective licensing flags embedded in an FFmpeg runtime."""

from __future__ import annotations

import re


_SWITCH = re.compile(
    r"--(?P<action>enable|disable)-(?P<features>\{[^}]+\}|[A-Za-z0-9_.+-]+)(?:=[^\s]+)?"
)


class RuntimeEvidenceError(ValueError):
    """Raised when an FFmpeg runtime cannot be proven license-safe for this app."""


def configuration_line(text: str) -> str:
    """Return FFmpeg's embedded configure arguments, or fail closed."""
    for line in text.splitlines():
        if "--target-os=" in line:
            return line
    raise RuntimeEvidenceError("FFmpeg configure arguments are not embedded in libavcodec")


def effective_ffmpeg_flags(configuration: str) -> dict[str, int]:
    """Derive CONFIG_GPL and CONFIG_NONFREE from FFmpeg's opt-in switches.

    FFmpeg 9.0.2 defaults both features off. An absent `--disable-*` switch is
    therefore not evidence that either feature was enabled; only an effective
    enable switch can set the corresponding generated CONFIG_* value to 1.
    Braced configure syntax such as `--enable-{gpl,version3}` is expanded too.
    """
    settings = {"gpl": 0, "nonfree": 0}
    for match in _SWITCH.finditer(configuration):
        feature_group = match.group("features")
        if feature_group.startswith("{"):
            features = [feature.strip() for feature in feature_group[1:-1].split(",")]
        else:
            features = [feature_group]
        value = int(match.group("action") == "enable")
        for feature in features:
            if feature in settings:
                settings[feature] = value
    return {"CONFIG_GPL": settings["gpl"], "CONFIG_NONFREE": settings["nonfree"]}


def verify_runtime_evidence(text: str) -> dict[str, int]:
    """Require a GPL/nonfree-free LGPL FFmpeg runtime containing the DCA decoder."""
    configuration = configuration_line(text)
    effective = effective_ffmpeg_flags(configuration)
    if effective["CONFIG_GPL"] != 0:
        raise RuntimeEvidenceError(f"effective CONFIG_GPL={effective['CONFIG_GPL']}; expected 0")
    if effective["CONFIG_NONFREE"] != 0:
        raise RuntimeEvidenceError(
            f"effective CONFIG_NONFREE={effective['CONFIG_NONFREE']}; expected 0"
        )

    required = {
        "FFmpeg n9.0 runtime": "FFmpeg version n9.0" in text,
        "LGPLv3-or-later license": "libavcodec license: LGPL version 3 or later" in text,
        "version-3 LGPL configuration": "--enable-version3" in configuration,
        "DCA decoder marker": "DCA (DTS Coherent Acoustics)" in text,
    }
    missing = [name for name, present in required.items() if not present]
    if missing:
        raise RuntimeEvidenceError(f"missing runtime evidence: {', '.join(missing)}")
    if re.search(r"(?<!L)GPL version", text, re.IGNORECASE):
        raise RuntimeEvidenceError("GPL-licensed FFmpeg runtime detected")
    if "license: nonfree and unredistributable" in text.lower():
        raise RuntimeEvidenceError("nonfree FFmpeg runtime detected")
    return effective
