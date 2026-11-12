#!/usr/bin/env python3
"""Patch the pinned FongMi VO with a bounded, opt-in one-shot readback bridge."""
from __future__ import annotations

import argparse
import shutil
from pathlib import Path

VO = Path("video/out/vo_gpu_next.c")
MESON = Path("meson.build")
MARKER = "MPVFLOW_FONGMI_VO_SINGLE_OUTPUT_CAPTURE_V2"
CHANNELS = (
    "source0", "source1", "pre_confidence_warp",
    "fallback_color", "trust_map", "synthesized",
)


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise ValueError(f"Pinned FongMi {label} anchor count is {count}; refusing an unverified patch")
    return source.replace(old, new, 1)


CAPTURE_HELPERS = r'''/* MPVFLOW_FONGMI_VO_SINGLE_OUTPUT_CAPTURE_V2 */
static const char *fongmi_capture_backend(const struct priv *p)
{
    return p->ra_ctx && p->ra_ctx->fns && p->ra_ctx->fns->type
        ? p->ra_ctx->fns->type : "unknown";
}

static void fongmi_capture_settings(const struct priv *p, const char *reason,
                                   char *settings, size_t settings_size)
{
    const char *route = p->fongmi_capture_route[0]
        ? p->fongmi_capture_route : "not_selected";
    const char *refusal = "see_terminal_reason";
    if (!reason)
        refusal = "none";
    else if (!strcmp(reason, "fongmi_diagnostic_actual_hdr_source_unsupported"))
        refusal = "actual_hdr_source_unsupported";
    else if (!strcmp(reason, "fongmi_diagnostic_planes_unavailable"))
        refusal = "diagnostic_planes_unavailable";
    snprintf(settings, settings_size,
        "{\"capture_mode\":\"fongmi_one_shot\",\"pair_limit\":1,"
        "\"sample_limit\":1,\"resolution_limit\":[1920,1080],"
        "\"max_bytes\":67108864,\"local_only\":true,"
        "\"active_route\":\"%s\",\"source_is_hdr\":%s,"
        "\"refusal_reason\":\"%s\","
        "\"transfer\":\"route-specific; no planes when stopped before a sample\","
        "\"plane_order\":[\"source0\",\"source1\","
        "\"pre_confidence_warp\",\"fallback_color\",\"trust_map\","
        "\"synthesized\"],\"trust_map_semantics\":"
        "\"RGB=applied FongMi trust scalar; alpha=0 denotes scene-cut branch\","
        "\"fallback_semantics\":\"exact source_blend before trust weighting\","
        "\"warp_semantics\":\"bidirectional warped blend before confidence weighting\"}",
        route, p->fongmi_capture_source_hdr ? "true" : "false", refusal);
}

static bool fongmi_capture_safe_id(const char *id)
{
    if (!id || !id[0])
        return false;
    for (const unsigned char *c = (const unsigned char *) id; *c; c++)
        if (!( (*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') ||
               (*c >= '0' && *c <= '9') || *c == '_' || *c == '-'))
            return false;
    return true;
}

static void fongmi_capture_terminal(struct vo *vo, const char *reason)
{
    struct priv *p = vo->priv;
    char settings[1536];
    fongmi_capture_settings(p, reason, settings, sizeof(settings));
    if (p->fongmi_capture_pending && p->fongmi_capture_base_dir[0] &&
        p->fongmi_capture_active_id[0]) {
        if (mpvflow_capture_io_write_single_terminal_manifest(
                p->fongmi_capture_base_dir, p->fongmi_capture_active_id,
                "mpv", fongmi_capture_backend(p), settings, reason) < 0)
            MP_WARN(vo, "FongMi diagnostic terminal manifest could not be written (%s).\n", reason);
    }
    if (p->motion)
        mp_motion_release_diagnostic_capture(p->motion);
    p->fongmi_capture_pending = false;
    p->fongmi_capture_armed = false;
    p->fongmi_capture_active_id[0] = '\0';
    p->fongmi_capture_route[0] = '\0';
    p->fongmi_capture_source_hdr = false;
}

static void fongmi_capture_poll_control(struct vo *vo)
{
    struct priv *p = vo->priv;
    const char *path = p->next_opts ? p->next_opts->fongmi_diagnostic_control_file : NULL;
    if (!path || !path[0])
        return;
    int64_t now = mp_time_ns();
    if (now <= 0 || now - p->fongmi_capture_poll_ns < 100000000LL)
        return;
    p->fongmi_capture_poll_ns = now;
    FILE *file = fopen(path, "rb");
    if (!file)
        return;
    char line[PATH_MAX + 160] = {0};
    char command[32] = {0}, session_id[81] = {0};
    char base_dir[PATH_MAX] = {0};
    bool have_line = fgets(line, sizeof(line), file) != NULL;
    fclose(file);
    if (!have_line)
        return;
    int fields = sscanf(line, "%31[^\t]\t%80[^\t]\t%4095[^\r\n]",
                        command, session_id, base_dir);
    if (fields < 2 || !fongmi_capture_safe_id(session_id))
        return;
    if (!strcmp(command, "STOP")) {
        if (p->fongmi_capture_pending &&
            !strcmp(session_id, p->fongmi_capture_active_id))
            fongmi_capture_terminal(vo, "stopped_before_first_synthesized_sample");
        return;
    }
    if (strcmp(command, "START_ONE_SHOT") || fields != 3 || !base_dir[0] ||
        !strcmp(session_id, p->fongmi_capture_last_id))
        return;
    if (p->fongmi_capture_pending)
        fongmi_capture_terminal(vo, "replaced_by_new_one_shot_request");
    snprintf(p->fongmi_capture_last_id, sizeof(p->fongmi_capture_last_id), "%s", session_id);
    snprintf(p->fongmi_capture_active_id, sizeof(p->fongmi_capture_active_id), "%s", session_id);
    snprintf(p->fongmi_capture_base_dir, sizeof(p->fongmi_capture_base_dir), "%s", base_dir);
    p->fongmi_capture_pending = true;
    p->fongmi_capture_armed = false;
    if (strcmp(fongmi_capture_backend(p), "vulkan"))
        fongmi_capture_terminal(vo, "vulkan_backend_required_for_bounded_readback");
}

static void fongmi_capture_arm(struct vo *vo, const struct pl_frame_mix *mix,
                               const int pair[2], float alpha,
                               const uint64_t *frame_ids, const char *route)
{
    struct priv *p = vo->priv;
    if (!p->fongmi_capture_pending || p->fongmi_capture_armed || !p->motion ||
        !mix || !mix->timestamps || pair[0] < 0 || pair[1] < 0 ||
        pair[0] >= mix->num_frames || pair[1] >= mix->num_frames)
        return;
    const struct mp_image *mpi0 = mix->frames[pair[0]]->user_data;
    const struct mp_image *mpi1 = mix->frames[pair[1]]->user_data;
    if (!mpi0 || !mpi1 || !isfinite(mpi0->pts) || !isfinite(mpi1->pts) ||
        !isfinite(mix->timestamps[pair[0]]) || !isfinite(alpha))
        return;
    p->fongmi_capture_pts0 = mpi0->pts;
    p->fongmi_capture_pts1 = mpi1->pts;
    p->fongmi_capture_output_pts = mpi0->pts - mix->timestamps[pair[0]];
    p->fongmi_capture_alpha = alpha;
    p->fongmi_capture_frame_id0 = frame_ids ? frame_ids[pair[0]] : 0;
    p->fongmi_capture_frame_id1 = frame_ids ? frame_ids[pair[1]] : 0;
    p->fongmi_capture_source_hdr =
        mp_motion_source_is_hdr(mix->frames[pair[0]]) ||
        mp_motion_source_is_hdr(mix->frames[pair[1]]);
    snprintf(p->fongmi_capture_route, sizeof(p->fongmi_capture_route), "%s",
             route ? route : "route_unknown");
    mp_motion_request_diagnostic_capture(p->motion, p->fongmi_capture_source_hdr);
    p->fongmi_capture_armed = true;
}

static const char *fongmi_capture_source_frame_route(
    const struct pl_frame_mix *mix, const int pair[2])
{
    const struct mp_image *mpi0 = mix->frames[pair[0]]->user_data;
    const struct mp_image *mpi1 = mix->frames[pair[1]]->user_data;
    if (!mpi0 || !mpi1 || !mpi0->priv || !mpi1->priv)
        return "source_route_unknown";
    const struct frame_priv *fp0 = mpi0->priv;
    const struct frame_priv *fp1 = mpi1->priv;
    return fp0->motion_frame.id && fp1->motion_frame.id
        ? "described_source_frames" : "raw_source_textures";
}

static uint8_t *fongmi_capture_download(struct priv *p, pl_tex texture,
                                       int width, int height)
{
    if (!texture || width <= 0 || height <= 0 ||
        (size_t) width > SIZE_MAX / 4 ||
        (size_t) height > SIZE_MAX / ((size_t) width * 4))
        return NULL;
    size_t row_pitch = (size_t) width * 4;
    uint8_t *pixels = malloc(row_pitch * (size_t) height);
    if (!pixels)
        return NULL;
    bool ok = pl_tex_download(p->gpu, pl_tex_transfer_params(
        .tex = texture,
        .rc = { .x0 = 0, .y0 = 0, .z0 = 0,
                .x1 = width, .y1 = height, .z1 = 1 },
        .row_pitch = row_pitch,
        .ptr = pixels));
    if (!ok) {
        free(pixels);
        return NULL;
    }
    return pixels;
}

static void fongmi_capture_finish(struct vo *vo)
{
    struct priv *p = vo->priv;
    if (!p->fongmi_capture_pending || !p->fongmi_capture_armed || !p->motion)
        return;
    struct mp_motion_diagnostic_capture capture = {0};
    if (!mp_motion_take_diagnostic_capture(p->motion, &capture))
        return;
    if (!capture.ready) {
        fongmi_capture_terminal(vo, capture.hdr_rejected
            ? "fongmi_diagnostic_actual_hdr_source_unsupported"
            : "fongmi_diagnostic_planes_unavailable");
        return;
    }
    char settings[1024];
    snprintf(settings, sizeof(settings),
        "{\"capture_mode\":\"fongmi_one_shot\",\"pair_limit\":1,"
        "\"sample_limit\":1,\"route_frame_ids\":[%" PRIu64 ",%" PRIu64 "],"
        "\"plane_order\":[\"source0\",\"source1\",\"pre_confidence_warp\","
        "\"fallback_color\",\"trust_map\",\"synthesized\"],"
        "\"transfer\":\"RGBA8; %s; trust scalar linear\","
        "\"trust_map_alpha\":\"0 on scene-cut branch; 255 on normal synthesis\","
        "\"fallback_semantics\":\"exact source_blend before trust weighting\","
        "\"local_only\":true}",
        p->fongmi_capture_frame_id0, p->fongmi_capture_frame_id1,
        capture.srgb_encoded ? "renderer-linear SDR color planes sRGB-encoded"
                             : "exact FongMi input transfer domain");
    size_t settings_len = strlen(settings);
    if (settings_len > 1 && settings[settings_len - 1] == '}') {
        settings[settings_len - 1] = '\0';
        snprintf(settings + settings_len - 1, sizeof(settings) - settings_len + 1,
            ",\"active_route\":\"%s\",\"source_is_hdr\":%s,"
            "\"refusal_reason\":\"none\"}",
            p->fongmi_capture_route, capture.source_hdr ? "true" : "false");
    }
    struct MPVFlowCaptureMeta meta = {
        .engine = "mpv",
        .backend = fongmi_capture_backend(p),
        .source_width = capture.width,
        .source_height = capture.height,
        .output_width = capture.width,
        .output_height = capture.height,
        .analysis_width = capture.analysis_width,
        .analysis_height = capture.analysis_height,
        .grid_width = capture.grid_width,
        .grid_height = capture.grid_height,
        .pts0 = p->fongmi_capture_pts0,
        .pts1 = p->fongmi_capture_pts1,
        .output_pts = p->fongmi_capture_output_pts,
        .timestep = p->fongmi_capture_alpha,
        .effective_settings_json = settings,
        .paired_output = 0,
        .soft_blend_enabled = 0,
        .visibility_resolver_enabled = 0,
        .preserve_motion_blend_on_conflict = 0,
        .single_output = 1,
    };
    char reason[160] = {0};
    struct MPVFlowCaptureSession *session = mpvflow_capture_io_start(
        p->fongmi_capture_base_dir, p->fongmi_capture_active_id, &meta,
        reason, sizeof(reason));
    if (!session) {
        fongmi_capture_terminal(vo, reason[0] ? reason : "capture_writer_start_failed");
        return;
    }
    static const char *channels[6] = {
        "source0", "source1", "pre_confidence_warp",
        "fallback_color", "trust_map", "synthesized",
    };
    for (int i = 0; i < 6; i++) {
        int sample_index = i < 2 ? -1 : 0;
        uint8_t *pixels = fongmi_capture_download(p, capture.planes[i], capture.width, capture.height);
        if (!pixels) {
            mpvflow_capture_io_missing(session, 0, sample_index, channels[i], "readback", "pl_tex_download_failed");
            continue;
        }
        if (mpvflow_capture_io_write(session, 0, sample_index, channels[i], &meta,
                                     pixels, capture.width, capture.height,
                                     reason, sizeof(reason)) < 0)
            mpvflow_capture_io_missing(session, 0, sample_index, channels[i], "writer", reason);
        free(pixels);
    }
    int complete = mpvflow_capture_io_stop(session);
    if (complete < 0)
        MP_WARN(vo, "FongMi diagnostic sample was incomplete: %s\n", mpvflow_capture_io_status(session));
    mpvflow_capture_io_destroy(session);
    mp_motion_release_diagnostic_capture(p->motion);
    p->fongmi_capture_pending = false;
    p->fongmi_capture_armed = false;
    p->fongmi_capture_active_id[0] = '\0';
    p->fongmi_capture_route[0] = '\0';
    p->fongmi_capture_source_hdr = false;
}
'''


def transform_vo(source: str) -> tuple[str, bool]:
    if "MPVFLOW_FONGMI_VO_SINGLE_OUTPUT_CAPTURE_V1" in source:
        raise ValueError("Stale FongMi VO diagnostic capture V1; rebuild from pinned source")
    if MARKER in source:
        needed = (
            "fongmi_capture_poll_control(vo)",
            "direct ? \"prepared_view_shader\" : \"prepared_view_texture\"",
            "fongmi_capture_source_frame_route(mix, pair)",
            "fongmi_capture_finish(vo)",
            "active_route",
            "fongmi_diagnostic_actual_hdr_source_unsupported",
            "fongmi-diagnostic-control-file",
            "mpvflow_capture_io_write",
        )
        if source.count(MARKER) != 1 or any(item not in source for item in needed):
            raise ValueError("FongMi VO capture marker/source mismatch")
        return source, False

    source = replace_once(source, "#include <sys/stat.h>\n#include <time.h>",
        "#include <sys/stat.h>\n#include <time.h>\n#include <limits.h>\n#include <math.h>\n#include <inttypes.h>\n#include <stdio.h>\n#include <stdlib.h>\n#include <string.h>",
        "capture system includes")
    source = replace_once(source, '#include "gpu_next/motion.h"',
        '#include "gpu_next/motion.h"\n#include "gpu_next/mpvflow_capture_io.h"',
        "capture writer include")
    source = replace_once(source, '#include "options/options.h"',
        '#include "options/options.h"\n#include "options/m_option.h"',
        "capture option flag include")
    source = replace_once(source, "    int motion_fps;\n    struct user_lut lut;",
        "    int motion_fps;\n    char *fongmi_diagnostic_control_file;\n    struct user_lut lut;",
        "capture control option field")
    source = replace_once(source,
        '        {"motion-interpolation", OPT_INT(motion_fps), M_RANGE(0, 240)},',
        '        {"motion-interpolation", OPT_INT(motion_fps), M_RANGE(0, 240)},\n'
        '        {"fongmi-diagnostic-control-file", OPT_STRING(fongmi_diagnostic_control_file), .flags = M_OPT_FILE},',
        "capture control option")
    source = replace_once(source, "    struct mp_motion *motion;\n    struct mp_motion_clock motion_clock;",
        """    struct mp_motion *motion;
    bool fongmi_capture_pending, fongmi_capture_armed;
    int64_t fongmi_capture_poll_ns;
    char fongmi_capture_base_dir[PATH_MAX];
    char fongmi_capture_active_id[81], fongmi_capture_last_id[81];
    char fongmi_capture_route[40];
    bool fongmi_capture_source_hdr;
    double fongmi_capture_pts0, fongmi_capture_pts1, fongmi_capture_output_pts;
    float fongmi_capture_alpha;
    uint64_t fongmi_capture_frame_id0, fongmi_capture_frame_id1;
    struct mp_motion_clock motion_clock;""",
        "capture session state")
    source = replace_once(source,
        "static bool render_source_mix(struct vo *vo, const struct vo_frame *frame,",
        CAPTURE_HELPERS + "\nstatic bool render_source_mix(struct vo *vo, const struct vo_frame *frame,",
        "capture helper insertion")
    source = replace_once(source,
        "    *motion_result = 0;\n    // A pre-seek redraw",
        "    *motion_result = 0;\n    fongmi_capture_poll_control(vo);\n    // A pre-seek redraw",
        "capture control polling")
    source = replace_once(source,
        "            bool direct = frame->vsync_interval > 0 && !frame->redraw &&\n"
        "                          !motion_quantizes_output(p, frame) &&\n"
        "                          mp_motion_shader_supported(p->motion);\n            bool ok;",
        "            bool direct = frame->vsync_interval > 0 && !frame->redraw &&\n"
        "                          !motion_quantizes_output(p, frame) &&\n"
        "                          mp_motion_shader_supported(p->motion);\n"
        "            fongmi_capture_arm(vo, mix, pair, motion.alpha, source.ids,\n"
        "                               direct ? \"prepared_view_shader\" : \"prepared_view_texture\");\n"
        "            bool ok;",
        "prepared-view capture arm")
    source = replace_once(source,
        "            *motion_result = ok && motion.synthesized ? 1 : -1;\n            return ok && motion.synthesized;",
        "            fongmi_capture_finish(vo);\n"
        "            *motion_result = ok && motion.synthesized ? 1 : -1;\n"
        "            return ok && motion.synthesized;",
        "prepared-view capture finish")
    source = replace_once(source,
        "    } else if (p->motion && can_interpolate) {\n        struct pl_frame output;",
        "    } else if (p->motion && can_interpolate) {\n"
        "        int pair[2];\n        float alpha;\n"
        "        if (select_motion_pair(mix, pair, &alpha) > 0)\n"
        "            fongmi_capture_arm(vo, mix, pair, alpha, source.ids,\n"
        "                               fongmi_capture_source_frame_route(mix, pair));\n"
        "        struct pl_frame output;",
        "source-frame capture arm")
    source = replace_once(source,
        "        *motion_result = prepare_motion_frame(vo, mix, &output);\n        if (*motion_result < 0)",
        "        *motion_result = prepare_motion_frame(vo, mix, &output);\n"
        "        fongmi_capture_finish(vo);\n"
        "        if (*motion_result < 0)",
        "source-frame capture finish")
    return source, True


def transform_meson(source: str) -> tuple[str, bool]:
    target = "    'video/out/gpu_next/motion.c',"
    capture = "    'video/out/gpu_next/mpvflow_capture_io.c',"
    if capture in source:
        if source.count(capture) != 1:
            raise ValueError("FongMi capture writer appears more than once in Meson source list")
        return source, False
    source = replace_once(source, target, target + "\n" + capture, "motion source list")
    return source, True


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mpv_dir", type=Path)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    mpv_dir = args.mpv_dir
    vo_path, meson_path = mpv_dir / VO, mpv_dir / MESON
    writer_dir = args.repo_root / "app/src/main/cpp/mpvflow"
    writer_c, writer_h = writer_dir / "mpvflow_capture_io.c", writer_dir / "mpvflow_capture_io.h"
    if not vo_path.is_file() or not meson_path.is_file() or not writer_c.is_file() or not writer_h.is_file():
        raise SystemExit("Pinned FongMi VO/Meson or bounded capture writer is missing")
    try:
        vo, vo_changed = transform_vo(vo_path.read_text(encoding="utf-8"))
        meson, meson_changed = transform_meson(meson_path.read_text(encoding="utf-8"))
    except ValueError as error:
        raise SystemExit(str(error)) from error
    target_dir = mpv_dir / "video/out/gpu_next"
    target_dir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(writer_c, target_dir / "mpvflow_capture_io.c")
    shutil.copyfile(writer_h, target_dir / "mpvflow_capture_io.h")
    if vo_changed:
        vo_path.write_text(vo, encoding="utf-8")
    if meson_changed:
        meson_path.write_text(meson, encoding="utf-8")
    print("FongMi one-shot VO/readback integration:",
          "patched" if vo_changed or meson_changed else "already patched")


if __name__ == "__main__":
    main()
