#!/usr/bin/env python3
"""Opt-in patch for the pinned FongMi native Jelly synthesis probe."""
from __future__ import annotations
import argparse
import hashlib
import shutil
from pathlib import Path

VO = Path("video/out/vo_gpu_next.c")
MESON = Path("meson.build")
MARKER = "MPVFLOW_FONGMI_VO_JELLY_PROBE_V1"


def replace_once(text: str, old: str, new: str, name: str) -> str:
    n = text.count(old)
    if n != 1:
        raise ValueError(f"{name}: expected one pinned anchor, found {n}")
    return text.replace(old, new, 1)


def apply_patch(vo_source_path, meson_path, repo_writer_dir, probe_enabled):
    """Patch the fetched MPV tree only when explicitly opted in."""
    vo_path, meson_file = Path(vo_source_path), Path(meson_path)
    if not probe_enabled:
        return False
    source, meson = vo_path.read_text(), meson_file.read_text()
    if MARKER in source or MARKER in meson:
        if MARKER not in source or MARKER not in meson:
            raise ValueError("partial Jelly probe patch detected")
        return False
    writer_dir = Path(repo_writer_dir)
    header, implementation = (writer_dir / "mpvrx_jelly_probe_writer.h",
                              writer_dir / "mpvrx_jelly_probe_writer.c")
    if not header.is_file() or not implementation.is_file():
        raise FileNotFoundError("writer C/H must be present in repo_writer_dir")

    include_anchor = '#include "gpu/utils.h"\n'
    source = replace_once(source, include_anchor, include_anchor +
        '#include "mpvrx_jelly_probe_writer.h"\n', "include")
    struct_anchor = '    struct mp_motion_clock motion_clock;\n'
    struct_fields = '''    struct mp_motion_clock motion_clock;
    /* MPVFLOW_FONGMI_VO_JELLY_PROBE_V1 */
    bool jelly_probe_armed, jelly_probe_pending, jelly_probe_writer_ready;
    bool jelly_probe_last_record_valid;
    pl_buf jelly_probe_buf;
    struct mpvrx_jelly_probe_writer jelly_probe_writer;
'''
    source = replace_once(source, struct_anchor, struct_fields, "priv fields")
    options_anchor = '    int motion_fps;\n    struct user_lut lut;\n'
    source = replace_once(source, options_anchor, options_anchor +
        '    char *fongmi_jelly_probe_report_file;\n    bool fongmi_jelly_probe_arm;\n', "option fields")
    table_anchor = '        {"motion-interpolation", OPT_INT(motion_fps), M_RANGE(0, 240)},\n'
    source = replace_once(source, table_anchor, table_anchor +
        '        {"fongmi-jelly-probe-report-file", OPT_STRING(fongmi_jelly_probe_report_file)},\n'
        '        {"fongmi-jelly-probe-arm", OPT_FLAG(fongmi_jelly_probe_arm)},\n', "option table")

    # Keep all file and worker setup in the control/update path, never in rendering.
    update_anchor = '    update_motion_options(vo);\n    publish_motion_info(vo);\n'
    setup = '''    update_motion_options(vo);
    publish_motion_info(vo);
    /* Probe resources are initialized only on an explicit control-path update. */
    if (p->next_opts->fongmi_jelly_probe_arm &&
        p->next_opts->fongmi_jelly_probe_report_file &&
        p->next_opts->fongmi_jelly_probe_report_file[0] && !p->jelly_probe_writer_ready) {
        static const unsigned char zero_record[MPVRX_JELLY_PROBE_RECORD_BYTES] = {0};
        struct pl_buf_params bp = {
            .size = MPVRX_JELLY_PROBE_RECORD_BYTES, .storable = true,
            .host_mapped = true, .host_readable = true,
            .memory_type = PL_BUF_MEM_DEVICE,
            .initial_data = zero_record,
        };
        if (p->gpu &&
            p->gpu->limits.max_buf_size >= MPVRX_JELLY_PROBE_RECORD_BYTES &&
            p->gpu->limits.max_ssbo_size >= MPVRX_JELLY_PROBE_RECORD_BYTES &&
            p->gpu->limits.max_mapped_size >= MPVRX_JELLY_PROBE_RECORD_BYTES &&
            p->gpu->limits.max_mapped_vram >= MPVRX_JELLY_PROBE_RECORD_BYTES)
            p->jelly_probe_buf = pl_buf_create(p->gpu, &bp);
        if (p->jelly_probe_buf && p->jelly_probe_buf->data) {
            if (mpvrx_jelly_probe_writer_init(&p->jelly_probe_writer,
                    p->next_opts->fongmi_jelly_probe_report_file) == 0 &&
                (p->jelly_probe_writer_ready = true) &&
                mpvrx_jelly_probe_writer_arm(&p->jelly_probe_writer) == MPVRX_JELLY_CAPTURE_ACCEPTED) {
                p->jelly_probe_writer_ready = true;
                p->jelly_probe_armed = true;
            } else {
                if (p->jelly_probe_writer_ready)
                    mpvrx_jelly_probe_writer_request_stop(&p->jelly_probe_writer);
                pl_buf_destroy(p->gpu, &p->jelly_probe_buf);
            }
        } else if (p->jelly_probe_buf) {
            pl_buf_destroy(p->gpu, &p->jelly_probe_buf);
        }
    }
'''
    source = replace_once(source, update_anchor, setup, "control update")

    # Callback-only poll: never wait, allocate, write files, or serialize JSON here.
    frame_anchor = 'static int prepare_motion_frame(struct vo *vo, const struct pl_frame_mix *mix,\n'
    poll = '''static void jelly_probe_poll(struct priv *p)
{
    if (!p->jelly_probe_pending || !p->jelly_probe_buf || !p->jelly_probe_writer_ready)
        return;
    if (pl_buf_poll(p->gpu, p->jelly_probe_buf, 0))
        return;
    p->jelly_probe_pending = false;
    p->jelly_probe_last_record_valid = true;
    if (mpvrx_jelly_probe_record_is_activation(p->jelly_probe_buf->data) &&
        mpvrx_jelly_probe_writer_capture(&p->jelly_probe_writer,
                                          p->jelly_probe_buf->data) == MPVRX_JELLY_CAPTURE_ACCEPTED)
        p->jelly_probe_armed = false;
}

'''
    source = replace_once(source, frame_anchor, poll + frame_anchor, "probe helpers insertion")
    source = replace_once(source, '    struct priv *p = vo->priv;\n    int before = -1;\n',
        '    struct priv *p = vo->priv;\n    jelly_probe_poll(p);\n    int before = -1;\n', "render poll")

    call_anchor = '''    if (described) {
        enum mp_motion_result result = mp_motion_render_frames(
            p->motion, &fp0->motion_frame, &fp1->motion_frame, alpha, output);
'''
    probe_call = '''    bool valid_probe_pts = isfinite(mpi0->pts) && isfinite(mpi1->pts) &&
        mpi0->pts != MP_NOPTS_VALUE && mpi1->pts != MP_NOPTS_VALUE;
    double probe_pts_min = valid_probe_pts ? fmin(mpi0->pts, mpi1->pts) : 0.0;
    double probe_pts_max = valid_probe_pts ? fmax(mpi0->pts, mpi1->pts) : 0.0;
    bool target_probe_pair = valid_probe_pts && probe_pts_min <= 1.63 && probe_pts_max >= 1.46;
    bool fallback_probe_pair = !valid_probe_pts || probe_pts_min > 1.63;
    bool probe_pair = p->jelly_probe_armed && !p->jelly_probe_pending &&
        p->jelly_probe_buf && (target_probe_pair || fallback_probe_pair) &&
        alpha >= 0.35f && alpha <= 0.65f;
    struct mp_motion_probe_request probe_request = {
        .buffer = p->jelly_probe_buf, .pixel_x = 1352, .pixel_y = 224,
        .first_id = fp0->motion_id, .second_id = fp1->motion_id,
        .first_pts = isfinite(mpi0->pts) && mpi0->pts != MP_NOPTS_VALUE ? mpi0->pts : NAN,
        .second_pts = isfinite(mpi1->pts) && mpi1->pts != MP_NOPTS_VALUE ? mpi1->pts : NAN,
        .alpha = alpha, .selection_mode = target_probe_pair ? 1 : 2,
    };
    if (described) {
        enum mp_motion_result result = probe_pair
            ? mp_motion_render_frames_probe(p->motion, &fp0->motion_frame,
                &fp1->motion_frame, alpha, &probe_request, output)
            : mp_motion_render_frames(p->motion, &fp0->motion_frame,
                &fp1->motion_frame, alpha, output);
        if (probe_pair && result == MP_MOTION_OK) {
            p->jelly_probe_pending = true;
        }
'''
    source = replace_once(source, call_anchor, probe_call, "described motion call")
    raw_anchor = '''        ok = mp_motion_render(p->motion, fp0->motion_texture, fp1->motion_texture,
                              fp0->motion_id, fp1->motion_id, alpha, &texture);
'''
    raw_new = '''        ok = probe_pair
            ? mp_motion_render_probe(p->motion, fp0->motion_texture, fp1->motion_texture,
                fp0->motion_id, fp1->motion_id, alpha, &probe_request, &texture)
            : mp_motion_render(p->motion, fp0->motion_texture, fp1->motion_texture,
                fp0->motion_id, fp1->motion_id, alpha, &texture);
        if (probe_pair && ok) {
            p->jelly_probe_pending = true;
        }
'''
    source = replace_once(source, raw_anchor, raw_new, "raw motion call")

    dtor_anchor = 'static void uninit(struct vo *vo)\n'
    destructor = '''static void jelly_probe_priv_destroy(void *opaque)
{
    struct priv *p = opaque;
    if (!p->jelly_probe_writer_ready)
        return;
    mpvrx_jelly_probe_writer_request_stop(&p->jelly_probe_writer);
    mpvrx_jelly_probe_writer_join(&p->jelly_probe_writer);
    mpvrx_jelly_probe_writer_destroy(&p->jelly_probe_writer);
    p->jelly_probe_writer_ready = false;
}

'''
    source = replace_once(source, dtor_anchor, destructor + dtor_anchor, "priv destructor")
    uninit_anchor = '    if (p->gpu)\n        pl_gpu_finish(p->gpu);\n'
    uninit_new = '''    if (p->gpu)
        pl_gpu_finish(p->gpu);
    jelly_probe_poll(p);
    if (p->jelly_probe_armed && p->jelly_probe_last_record_valid &&
        p->jelly_probe_buf && p->jelly_probe_writer_ready &&
        mpvrx_jelly_probe_writer_capture(&p->jelly_probe_writer,
                                          p->jelly_probe_buf->data) == MPVRX_JELLY_CAPTURE_ACCEPTED)
        p->jelly_probe_armed = false;
    p->jelly_probe_armed = false;
    if (p->gpu)
        pl_buf_destroy(p->gpu, &p->jelly_probe_buf);
'''
    source = replace_once(source, uninit_anchor, uninit_new, "GPU-safe uninit")
    # The VO's talloc-owned priv destructor runs after vo_destroy joined its thread.
    source = replace_once(source, '    struct priv *p = vo->priv;\n',
        '    struct priv *p = vo->priv;\n', "priv setup anchor") if False else source
    # Registered from the existing preinit once p is allocated.
    preinit = '    struct priv *p = vo->priv;\n'
    first = source.find(preinit)
    if first < 0:
        raise ValueError("preinit priv initialization anchor missing")
    # locate the actual preinit function before editing to avoid unrelated handlers
    start = source.find('static int preinit(struct vo *vo)')
    if start < 0:
        raise ValueError("preinit anchor missing")
    pos = source.find(preinit, start)
    source = source[:pos] + source[pos:].replace(preinit,
        preinit + '    talloc_set_destructor(p, jelly_probe_priv_destroy);\n', 1)

    meson_anchor = "    'video/out/vo_gpu_next.c',\n"
    meson = replace_once(meson, meson_anchor, meson_anchor +
        "    'video/out/mpvrx_jelly_probe_writer.c',\n    # " + MARKER + "\n", "Meson source")
    shutil.copy2(header, vo_path.parent / header.name)
    shutil.copy2(implementation, vo_path.parent / implementation.name)
    vo_path.write_text(source)
    meson_file.write_text(meson)
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('vo_source_path', type=Path)
    ap.add_argument('meson_path', type=Path)
    ap.add_argument('repo_writer_dir', type=Path)
    ap.add_argument('--probe-enabled', action='store_true')
    args = ap.parse_args()
    apply_patch(args.vo_source_path, args.meson_path, args.repo_writer_dir,
                args.probe_enabled)

if __name__ == '__main__':
    main()
