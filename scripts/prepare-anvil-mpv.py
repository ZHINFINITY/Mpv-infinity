#!/usr/bin/env python3
"""Register the vendored ANVIL filter in the pinned MPV checkout for Android."""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "app/src/main/cpp/anvil"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one {label} anchor, found {count}")
    return text.replace(old, new, 1)


def patch_filter(source: str) -> str:
    qnn_includes = '''// QNN headers (compile-time types only; runtime via dlopen)
#include "QNN/QnnInterface.h"
#include "QNN/System/QnnSystemInterface.h"
#include "QNN/HTP/QnnHtpPerfInfrastructure.h"
#include "QNN/HTP/QnnHtpDevice.h"'''
    qnn_fallback_includes = '''// QAIRT supplies the QNN_SDK_STUB marker in CI. In that mode retain only
// opaque storage types; model/HTP entry points compile to explicit unavailable
// stubs, while ANVIL's genuine Vulkan/CPU midpoint path remains live.
#include "QNN/QnnInterface.h"
#ifdef QNN_SDK_STUB
typedef struct { uintptr_t opaque[32]; } AnvilQnnInterfaceStub;
typedef struct { uintptr_t opaque[32]; } AnvilQnnSystemInterfaceStub;
#define QNN_INTERFACE_VER_TYPE AnvilQnnInterfaceStub
#define QNN_SYSTEM_INTERFACE_VER_TYPE AnvilQnnSystemInterfaceStub
typedef struct { uintptr_t opaque[32]; } Qnn_Tensor_t;
typedef void *Qnn_BackendHandle_t;
typedef void *Qnn_ContextHandle_t;
typedef void *Qnn_GraphHandle_t;
typedef void *QnnSystemContext_Handle_t;
typedef int Qnn_ErrorHandle_t;
#ifndef QNN_SUCCESS
#define QNN_SUCCESS 0
#endif
#else
#include "QNN/System/QnnSystemInterface.h"
#include "QNN/HTP/QnnHtpPerfInfrastructure.h"
#include "QNN/HTP/QnnHtpDevice.h"
#endif'''
    source = replace_once(source, qnn_includes, qnn_fallback_includes, "QNN include block")
    source = replace_once(
        source,
        "#include <math.h>\n#include <string.h>",
        "#include <math.h>\n#include <stdint.h>\n#include <string.h>",
        "stdint include",
    )

    qnn_start = source.index("static int qnn_init(struct qnn_state *q, struct mp_filter *f, int W, int H)")
    qnn_end = source.index("// ====================================================================\n// Section 8: Filter state and workspace", qnn_start)
    real_qnn = source[qnn_start:qnn_end].rstrip()
    qnn_stubs = '''#ifdef QNN_SDK_STUB
static int qnn_init(struct qnn_state *q, struct mp_filter *f, int W, int H)
{
    (void)W;
    (void)H;
    memset(q, 0, sizeof(*q));
    MP_INFO(f, "ANVIL[CAPABILITY]: qnn=disabled reason=QAIRT_not_bundled model_path=%s\\n",
            qnn_get_dir());
    return -1;
}

static void qnn_switch_buffer(struct qnn_state *q, int idx)
{
    (void)q;
    (void)idx;
}

static Qnn_ErrorHandle_t qnn_execute(struct qnn_state *q)
{
    (void)q;
    return -1;
}

static void qnn_cleanup(struct qnn_state *q)
{
    if (q)
        memset(q, 0, sizeof(*q));
}
#else
'''
    source = source[:qnn_start] + qnn_stubs + real_qnn + "\n#endif // QNN_SDK_STUB\n\n" + source[qnn_end:]

    worker_match = re.search(r"static void \*htp_thread_fn\(void \*arg\)\n\{.*?\n\}", source, re.S)
    if not worker_match:
        raise SystemExit("Could not locate ANVIL HTP worker")
    worker = worker_match.group(0)
    worker_fallback = '''#ifdef QNN_SDK_STUB
static void *htp_thread_fn(void *arg)
{
    (void)arg;
    return NULL;
}
#else
''' + worker + "\n#endif // QNN_SDK_STUB"
    source = source[:worker_match.start()] + worker_fallback + source[worker_match.end():]

    source = replace_once(
        source,
        "struct priv {\n    enum anvil_state state;\n    int frame_count;",
        "struct priv {\n    enum anvil_state state;\n    int frame_count;\n    int last_motion_vectors;\n    int last_backend_vulkan;\n    uint64_t generated_frames;",
        "filter counters",
    )
    source = replace_once(
        source,
        "        int active;                   // 1 = HTP in flight for this slot\n        int use_quant_path;",
        "        int active;                   // 1 = HTP in flight for this slot\n"
        "        int input_frame_count;\n        int motion_vectors;\n        int backend_vulkan;\n"
        "        int use_quant_path;",
        "pending-frame telemetry",
    )

    start_interp_match = re.search(
        r"static void start_interpolation\(struct priv \*p, struct mp_filter \*f,\n"
        r".*?\n\s*\{",
        source,
        re.S,
    )
    if not start_interp_match:
        raise SystemExit("Could not locate ANVIL start_interpolation function")
    insertion = (
        "\n    p->pend[p->pend_cur].input_frame_count = p->frame_count;"
        "\n    p->pend[p->pend_cur].motion_vectors = n_mvs;"
        "\n    p->pend[p->pend_cur].backend_vulkan = 1;"
    )
    source = source[:start_interp_match.end()] + insertion + source[start_interp_match.end():]

    helper = '''static void report_generated_frame(struct mp_filter *f, struct priv *p,
                                   int input_frame_count, int motion_vectors,
                                   int backend_vulkan)
{
    p->generated_frames++;
    int interval = p->log_interval > 0 ? p->log_interval : 30;
    if (p->generated_frames == 1 || p->generated_frames % interval == 0) {
        MP_INFO(f, "ANVIL[GENERATED]: count=%llu input_frame=%d motion_vectors=%d backend=%s qnn=%s\\n",
                (unsigned long long)p->generated_frames, input_frame_count,
                motion_vectors, backend_vulkan ? "vulkan" : "cpu",
                p->qnn.ready ? "active" : "disabled");
    }
}

'''
    source = replace_once(
        source,
        "static void f_process(struct mp_filter *f)\n{",
        helper + "static void f_process(struct mp_filter *f)\n{",
        "generated-frame reporter",
    )
    source = replace_once(
        source,
        '''        struct mp_image *out;
        int slot = p->pend_cur;
        if (p->pend[slot].active) {
            out = finish_interpolation(p, f, slot);
        } else {
            out = p->interp;
            p->interp = NULL;
        }
        mp_pin_in_write(f->ppins[1], MAKE_FRAME(MP_FRAME_VIDEO, out));''',
        '''        struct mp_image *out;
        int slot = p->pend_cur;
        int input_frame_count = p->frame_count;
        int motion_vectors = p->last_motion_vectors;
        int backend_vulkan = p->last_backend_vulkan;
        if (p->pend[slot].active) {
            input_frame_count = p->pend[slot].input_frame_count;
            motion_vectors = p->pend[slot].motion_vectors;
            backend_vulkan = p->pend[slot].backend_vulkan;
            out = finish_interpolation(p, f, slot);
        } else {
            out = p->interp;
            p->interp = NULL;
        }
        if (out)
            report_generated_frame(f, p, input_frame_count, motion_vectors, backend_vulkan);
        mp_pin_in_write(f->ppins[1], MAKE_FRAME(MP_FRAME_VIDEO, out));''',
        "first generated midpoint output",
    )
    source = replace_once(
        source,
        '''            break;
        }
    }

    if (p->frame_count % 60 == 1) {
        MP_INFO(f, "ANVIL: frame %d, %dx%d, %d MVs, qnn=%d, async=%d\\n",
                p->frame_count, W, H, n_mvs, p->qnn.ready, p->htp_thread_created);
    }

    // First frame''',
        '''            break;
        }
    }
    p->last_motion_vectors = n_mvs;

    if (p->frame_count % 60 == 1) {
        MP_INFO(f, "ANVIL[INPUT]: frames=%d resolution=%dx%d motion_vectors=%d\\n",
                p->frame_count, W, H, n_mvs);
    }

    // First frame''',
        "structured input/MV telemetry",
    )
    source = replace_once(
        source,
        "    int use_vk = p->vk.ready && p->vk.W == W && p->vk.H == H;\n"
        "    int use_async = use_vk && p->qnn.ready && p->qnn.input_is_quantized",
        "    int use_vk = p->vk.ready && p->vk.W == W && p->vk.H == H;\n"
        "    p->last_backend_vulkan = use_vk;\n"
        "    int use_async = use_vk && p->qnn.ready && p->qnn.input_is_quantized",
        "active interpolation backend tracking",
    )
    source = replace_once(
        source,
        '''            // Step 2: Finish PREVIOUS interpolation (wait HTP — should be fast due to overlap)
            struct mp_image *prev_interp = finish_interpolation(p, f, prev_slot);

            // Step 3: Output previous interp frame
            mp_pin_in_write(f->ppins[1], MAKE_FRAME(MP_FRAME_VIDEO, prev_interp));''',
        '''            // Step 2: Finish PREVIOUS interpolation (wait HTP — should be fast due to overlap)
            int prev_input_frame_count = p->pend[prev_slot].input_frame_count;
            int prev_motion_vectors = p->pend[prev_slot].motion_vectors;
            int prev_backend_vulkan = p->pend[prev_slot].backend_vulkan;
            struct mp_image *prev_interp = finish_interpolation(p, f, prev_slot);

            // Step 3: Output previous interp frame
            if (prev_interp)
                report_generated_frame(f, p, prev_input_frame_count, prev_motion_vectors,
                                       prev_backend_vulkan);
            mp_pin_in_write(f->ppins[1], MAKE_FRAME(MP_FRAME_VIDEO, prev_interp));''',
        "overlapped generated midpoint output",
    )
    source = replace_once(
        source,
        '''        if (vk_init(&p->vk, f, W, H) == 0) {
            MP_INFO(f, "ANVIL: Vulkan GPU compute ready (%dx%d)\\n", W, H);
        } else {
            MP_INFO(f, "ANVIL: Vulkan not available, using CPU fallback\\n");
            vk_cleanup(&p->vk);
        }''',
        '''        if (vk_init(&p->vk, f, W, H) == 0) {
            MP_INFO(f, "ANVIL: Vulkan GPU compute ready (%dx%d)\\n", W, H);
            MP_INFO(f, "ANVIL[CAPABILITY]: backend=vulkan qnn=%s\\n",
                    p->qnn.ready ? "active" : "disabled");
        } else {
            MP_INFO(f, "ANVIL: Vulkan not available, using CPU fallback\\n");
            MP_INFO(f, "ANVIL[CAPABILITY]: backend=cpu qnn=%s\\n",
                    p->qnn.ready ? "active" : "disabled");
            vk_cleanup(&p->vk);
        }''',
        "backend capability telemetry",
    )

    reset_start = source.index("static void f_reset(struct mp_filter *f)\n{")
    reset_end = source.index("static void f_destroy(struct mp_filter *f)", reset_start)
    reset_region = source[reset_start:reset_end]
    reset_region = replace_once(
        reset_region,
        "    struct priv *p = f->priv;",
        "    struct priv *p = f->priv;\n"
        "    p->frame_count = 0;\n"
        "    p->last_motion_vectors = 0;\n"
        "    p->last_backend_vulkan = 0;\n"
        "    p->generated_frames = 0;\n"
        "    MP_INFO(f, \"ANVIL[RESET]: generated_frames=0\\n\");",
        "frame counter reset",
    )
    source = source[:reset_start] + reset_region + source[reset_end:]
    source = replace_once(
        source,
        'MP_INFO(f, "ANVIL VFI frame-doubler (Vulkan GPU + HTP, log_interval=%d)\\n", p->log_interval);',
        'MP_INFO(f, "ANVIL[CAPABILITY]: filter=anvil state=created qnn=unknown backend=unknown (log_interval=%d)\\n", p->log_interval);',
        "filter-created capability log",
    )
    return source


def base_sources_close(meson: str) -> int:
    start = meson.index("sources = files(")
    depth = 0
    quote = None
    escaped = False
    comment = False
    for pos in range(start, len(meson)):
        char = meson[pos]
        if comment:
            if char == "\n":
                comment = False
            continue
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char == "#":
            comment = True
        elif char in "'\"":
            quote = char
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return pos
    raise SystemExit("Could not find end of MPV base source list")


def patch_mpv(mpv_dir: Path, filter_source: str) -> None:
    meson_path = mpv_dir / "meson.build"
    user_h_path = mpv_dir / "filters/user_filters.h"
    user_c_path = mpv_dir / "filters/user_filters.c"
    for path in (meson_path, user_h_path, user_c_path):
        if not path.is_file():
            raise SystemExit(f"Pinned MPV source tree is missing {path.relative_to(mpv_dir)}")

    video_dir = mpv_dir / "video/filter"
    video_dir.mkdir(parents=True, exist_ok=True)
    for name in (
        "median5_spv.h",
        "gauss_sep_spv.h",
        "warp_pack_spv.h",
        "warp_pack_quant_spv.h",
        "residual_yuv_spv.h",
    ):
        shutil.copy2(VENDOR / name, video_dir / name)
    (video_dir / "vf_anvil.c").write_text(filter_source)

    meson = meson_path.read_text()
    if "ANVIL_ANDROID_FILTER" not in meson:
        close = base_sources_close(meson)
        insertion = '''

# ANVIL is built only for Android; use the platform Vulkan loader for compute.
if host_machine.system() == 'android'
    sources += files('video/filter/vf_anvil.c')
    anvil_vulkan_dep = meson.get_compiler('c').find_library('vulkan', required: true)
    dependencies += [anvil_vulkan_dep]
endif
# ANVIL_ANDROID_FILTER
'''
        meson = meson[:close + 1] + insertion + meson[close + 1:]
    meson_path.write_text(meson)

    user_h = user_h_path.read_text()
    if "vf_anvil" not in user_h:
        user_h = replace_once(
            user_h,
            "extern const struct mp_user_filter_entry vf_sub;",
            "extern const struct mp_user_filter_entry vf_sub;\n"
            "#ifdef __ANDROID__\nextern const struct mp_user_filter_entry vf_anvil;\n#endif",
            "MPV user-filter declaration",
        )
    user_h_path.write_text(user_h)

    user_c = user_c_path.read_text()
    if "&vf_anvil" not in user_c:
        user_c = replace_once(
            user_c,
            "    &vf_sub,",
            "    &vf_sub,\n#ifdef __ANDROID__\n    &vf_anvil,\n#endif",
            "MPV user-filter registry",
        )
    user_c_path.write_text(user_c)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mpv-dir", required=True, type=Path)
    args = parser.parse_args()
    mpv_dir = args.mpv_dir.resolve()
    source_path = VENDOR / "vf_anvil.c"
    if not source_path.is_file():
        raise SystemExit(f"Vendored ANVIL source not found: {source_path}")
    patched_source = patch_filter(source_path.read_text())
    patch_mpv(mpv_dir, patched_source)
    print(f"Prepared real ANVIL filter in {mpv_dir}")


if __name__ == "__main__":
    main()
