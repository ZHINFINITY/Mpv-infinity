#!/usr/bin/env python3
"""Patch FFmpeg 9.0.2's software HEVC decoder to export AVMotionVector side data for ANVIL."""
from __future__ import annotations

import sys
from pathlib import Path


PATCH = r'''/* MPV∞ ANVIL: adapt FFmpeg's HEVC prediction fields to AVMotionVector. */
typedef struct HEVCExportCandidate {
    int source;
    int32_t motion_x;
    int32_t motion_y;
} HEVCExportCandidate;

typedef struct HEVCExportRun {
    int x_pu;
    int width_px;
    int height_px;
    int source;
    int32_t motion_x;
    int32_t motion_y;
    size_t vector_index;
} HEVCExportRun;

static int hevc_get_export_candidate(const HEVCFrame *frame, int x_pu, int y_pu,
                                     int list, HEVCExportCandidate *candidate)
{
    const HEVCSPS *sps;
    const MvField *field;
    const RefPicListTab *rpl_tab;
    const RefPicList *rpl;
    int pu_size, ctb_x, ctb_y, ctb_addr_rs, ctb_addr_ts;
    int ref_idx, ref_poc;
    int pred_flag = list == 0 ? PF_L0 : PF_L1;

    if (!frame || !frame->pps || !frame->rpl_tab || !frame->tab_mvf)
        return 0;
    sps = frame->pps->sps;
    if (!sps || x_pu < 0 || y_pu < 0 ||
        x_pu >= sps->min_pu_width || y_pu >= sps->min_pu_height)
        return 0;

    field = &frame->tab_mvf[y_pu * sps->min_pu_width + x_pu];
    if (!(field->pred_flag & pred_flag))
        return 0;

    pu_size = 1 << sps->log2_min_pu_size;
    ctb_x = (x_pu * pu_size) >> sps->log2_ctb_size;
    ctb_y = (y_pu * pu_size) >> sps->log2_ctb_size;
    if (ctb_x < 0 || ctb_x >= sps->ctb_width || ctb_y < 0 ||
        ctb_y * sps->ctb_width + ctb_x >= frame->ctb_count)
        return 0;

    ctb_addr_rs = ctb_y * sps->ctb_width + ctb_x;
    ctb_addr_ts = frame->pps->ctb_addr_rs_to_ts ?
                  frame->pps->ctb_addr_rs_to_ts[ctb_addr_rs] : ctb_addr_rs;
    if (ctb_addr_ts < 0 || ctb_addr_ts >= frame->ctb_count ||
        !frame->rpl_tab[ctb_addr_ts])
        return 0;

    rpl_tab = frame->rpl_tab[ctb_addr_ts];
    rpl = &rpl_tab->refPicList[list];
    ref_idx = field->ref_idx[list];
    if (ref_idx < 0 || ref_idx >= rpl->nb_refs || ref_idx >= HEVC_MAX_REFS)
        return 0;

    /* RefPicList.list stores the resolved POC, including long-term POC wrap. */
    ref_poc = rpl->list[ref_idx];
    if (ref_poc == frame->poc)
        return 0;

    candidate->source = ref_poc < frame->poc ? -1 : 1;
    /* HEVC Mv components are quarter-luma-sample units (see motion compensation's >> 2). */
    candidate->motion_x = field->mv[list].x;
    candidate->motion_y = field->mv[list].y;
    return 1;
}

static int hevc_export_candidate_equal(const HEVCExportCandidate *a,
                                       const HEVCExportCandidate *b)
{
    return a->source == b->source &&
           a->motion_x == b->motion_x &&
           a->motion_y == b->motion_y;
}

static int hevc_append_export_vector(AVMotionVector **vectors, size_t *count,
                                    size_t *capacity, const AVMotionVector *value,
                                    size_t *index)
{
    if (*count == *capacity) {
        size_t new_capacity = *capacity ? *capacity * 2 : 256;
        AVMotionVector *next;

        if (new_capacity < *capacity)
            return AVERROR(ENOMEM);
        next = av_realloc_array(*vectors, new_capacity, sizeof(**vectors));
        if (!next)
            return AVERROR(ENOMEM);
        *vectors = next;
        *capacity = new_capacity;
    }

    *index = *count;
    (*vectors)[(*count)++] = *value;
    return 0;
}

static void hevc_export_motion_vectors(HEVCContext *s, const HEVCFrame *frame,
                                      AVFrame *output)
{
    const HEVCSPS *sps;
    AVMotionVector *vectors = NULL;
    HEVCExportRun *previous = NULL, *current = NULL;
    size_t vector_count = 0, vector_capacity = 0;
    int pu_width, pu_height, pu_size, max_run_pus;
    int list, y, x;

    if (!(s->avctx->export_side_data & AV_CODEC_EXPORT_DATA_MVS))
        return;

    /* A reused output frame must never retain vectors from its previous picture. */
    av_frame_remove_side_data(output, AV_FRAME_DATA_MOTION_VECTORS);
    if (!frame || !frame->pps || !frame->tab_mvf || !frame->rpl_tab)
        return;

    sps = frame->pps->sps;
    if (!sps || frame->ctb_count <= 0)
        return;
    pu_width = sps->min_pu_width;
    pu_height = sps->min_pu_height;
    pu_size = 1 << sps->log2_min_pu_size;
    max_run_pus = UINT8_MAX / pu_size;
    if (pu_width <= 0 || pu_height <= 0 || pu_size <= 0 || max_run_pus <= 0)
        return;

    /* Merge identical neighboring 4x4 motion fields into AVMotionVector rectangles. */
    previous = av_malloc_array(pu_width, sizeof(*previous));
    current = av_malloc_array(pu_width, sizeof(*current));
    if (!previous || !current)
        goto end;

    for (list = 0; list < 2; list++) {
        size_t previous_count = 0;

        for (y = 0; y < pu_height; y++) {
            size_t current_count = 0;
            size_t previous_cursor = 0;

            for (x = 0; x < pu_width;) {
                HEVCExportCandidate first;
                int run_pus = 1;
                int width_px;
                int merged = 0;
                size_t vector_index = 0;
                HEVCExportRun run;

                if (!hevc_get_export_candidate(frame, x, y, list, &first)) {
                    x++;
                    continue;
                }

                while (run_pus < max_run_pus && x + run_pus < pu_width) {
                    HEVCExportCandidate next;
                    if (!hevc_get_export_candidate(frame, x + run_pus, y, list, &next) ||
                        !hevc_export_candidate_equal(&first, &next))
                        break;
                    run_pus++;
                }
                width_px = run_pus * pu_size;

                while (previous_cursor < previous_count &&
                       previous[previous_cursor].x_pu < x)
                    previous_cursor++;
                if (previous_cursor < previous_count) {
                    HEVCExportRun *prior = &previous[previous_cursor];
                    if (prior->x_pu == x && prior->width_px == width_px &&
                        prior->source == first.source &&
                        prior->motion_x == first.motion_x &&
                        prior->motion_y == first.motion_y &&
                        prior->height_px + pu_size <= UINT8_MAX) {
                        AVMotionVector *vector = &vectors[prior->vector_index];
                        vector->h += pu_size;
                        vector->dst_y += pu_size / 2;
                        vector->src_y = vector->dst_y +
                                        vector->motion_y / vector->motion_scale;
                        run = *prior;
                        run.height_px += pu_size;
                        vector_index = prior->vector_index;
                        merged = 1;
                    }
                }

                if (!merged) {
                    AVMotionVector value = { 0 };
                    const int dst_x = x * pu_size + width_px / 2;
                    const int dst_y = y * pu_size + pu_size / 2;

                    value.source = first.source;
                    value.w = width_px;
                    value.h = pu_size;
                    value.dst_x = dst_x;
                    value.dst_y = dst_y;
                    value.motion_x = first.motion_x;
                    value.motion_y = first.motion_y;
                    value.motion_scale = 4;
                    value.src_x = dst_x + first.motion_x / value.motion_scale;
                    value.src_y = dst_y + first.motion_y / value.motion_scale;
                    if (hevc_append_export_vector(&vectors, &vector_count,
                                                  &vector_capacity, &value,
                                                  &vector_index) < 0)
                        goto end;

                    run.x_pu = x;
                    run.width_px = width_px;
                    run.height_px = pu_size;
                    run.source = first.source;
                    run.motion_x = first.motion_x;
                    run.motion_y = first.motion_y;
                    run.vector_index = vector_index;
                }

                current[current_count++] = run;
                x += run_pus;
            }

            {
                HEVCExportRun *swap = previous;
                previous = current;
                current = swap;
            }
            previous_count = current_count;
        }
    }

    if (vector_count && vector_count <= SIZE_MAX / sizeof(*vectors)) {
        size_t bytes = vector_count * sizeof(*vectors);
        AVFrameSideData *side_data = av_frame_new_side_data(
            output, AV_FRAME_DATA_MOTION_VECTORS, bytes);
        if (side_data)
            memcpy(side_data->data, vectors, bytes);
    }

end:
    av_freep(&previous);
    av_freep(&current);
    av_freep(&vectors);
}
'''


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {label} anchor; found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch-ffmpeg-hevc-mvs.py <ffmpeg-source-dir>")

    root = Path(sys.argv[1]).resolve()
    version = root / "libavcodec/version_major.h"
    refs_path = root / "libavcodec/hevc/refs.c"
    if not version.is_file() or not refs_path.is_file():
        raise SystemExit(f"Not an FFmpeg source tree: {root}")
    if "#define LIBAVCODEC_VERSION_MAJOR  63" not in version.read_text():
        raise SystemExit("HEVC MV adapter is pinned to FFmpeg 9.x (libavcodec major 63)")

    refs = refs_path.read_text()
    if "MPV∞ ANVIL: adapt FFmpeg's HEVC prediction fields" in refs:
        print("HEVC motion-vector export patch is already applied")
        return

    refs = replace_once(
        refs,
        '#include "libavutil/mem.h"\n#include "libavutil/stereo3d.h"',
        '#include "libavutil/mem.h"\n#include "libavutil/motion_vector.h"\n#include "libavutil/stereo3d.h"',
        "motion-vector include",
    )
    refs = replace_once(
        refs,
        "int ff_hevc_output_frames(HEVCContext *s,\n",
        PATCH + "\nint ff_hevc_output_frames(HEVCContext *s,\n",
        "HEVC frame output function",
    )
    refs = replace_once(
        refs,
        "            if (output) {\n                if (frame->flags & HEVC_FRAME_FLAG_CORRUPT)",
        "            if (output) {\n"
        "                if (s->avctx->export_side_data & AV_CODEC_EXPORT_DATA_MVS)\n"
        "                    hevc_export_motion_vectors(s, frame, f);\n"
        "                if (frame->flags & HEVC_FRAME_FLAG_CORRUPT)",
        "HEVC output side-data call",
    )
    refs_path.write_text(refs)
    print(f"Patched FFmpeg 9.x HEVC motion-vector export in {refs_path}")


if __name__ == "__main__":
    main()
