#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BRIDGE="$ROOT/app/src/main/cpp/rife/rife_vfi_bridge.cpp"
BUILD_SCRIPT="$ROOT/scripts/build-rife-native.sh"

if ! grep -Fq -- '-DNCNN_SHARED_LIB=OFF' "$BUILD_SCRIPT"; then
    echo "RIFE must link NCNN statically for the NCNN Vulkan dispatch globals" >&2
    exit 1
fi

python3 - "$BRIDGE" <<'PY'
from pathlib import Path
import re
import sys

source = Path(sys.argv[1]).read_text()
direct = re.search(r"(?<![A-Za-z0-9_:])vkGetPhysicalDeviceImageFormatProperties2\s*\(", source)
if direct:
    raise SystemExit("RIFE bridge directly links vkGetPhysicalDeviceImageFormatProperties2; use NCNN's KHR dispatch pointer")

call = "VkResult result = ncnn::vkGetPhysicalDeviceImageFormatProperties2KHR("
if call not in source:
    raise SystemExit("RIFE bridge is missing the dynamically loaded NCNN KHR image-format query")
call_at = source.index(call)
guard_at = source.rfind("if (", 0, call_at)
guard = source[guard_at:call_at]
required_guards = (
    "ncnn::support_VK_KHR_get_physical_device_properties2 <= 0",
    "!ncnn::vkGetPhysicalDeviceImageFormatProperties2KHR",
)
missing = [item for item in required_guards if item not in guard]
if missing:
    raise SystemExit("RIFE KHR dispatch is not fail-closed; missing guard(s): " + ", ".join(missing))

print("RIFE Android Vulkan dispatch regression test passed")
PY
