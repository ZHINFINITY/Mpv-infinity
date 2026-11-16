from __future__ import annotations
import importlib.util
import os
import shutil
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PINNED = Path(os.environ.get('MPVRX_JELLY_PROBE_MPV_DIR', '/workspace/mpvrx-pinned-mpv-95ec8e9'))
SPEC = importlib.util.spec_from_file_location(
    'probe_patch', ROOT / 'scripts/patch_fongmi_vo_jelly_probe.py')
PATCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCH)


class JellyProbePatchTest(unittest.TestCase):
    def fixture(self):
        temp = tempfile.TemporaryDirectory()
        root = Path(temp.name)
        vo = root / 'video/out/vo_gpu_next.c'
        meson = root / 'meson.build'
        vo.parent.mkdir(parents=True)
        shutil.copy2(PINNED / 'video/out/vo_gpu_next.c', vo)
        shutil.copy2(PINNED / 'meson.build', meson)
        writer = root / 'writer'
        writer.mkdir()
        (writer / 'mpvrx_jelly_probe_writer.h').write_text('/* writer header */\n')
        (writer / 'mpvrx_jelly_probe_writer.c').write_text('/* writer source */\n')
        return temp, vo, meson, writer

    def test_non_opt_in_is_byte_for_byte_noop(self):
        temp, vo, meson, writer = self.fixture()
        with temp:
            before = vo.read_bytes(), meson.read_bytes()
            self.assertFalse(PATCH.apply_patch(vo, meson, writer, False))
            self.assertEqual(before, (vo.read_bytes(), meson.read_bytes()))
            self.assertFalse((vo.parent / 'mpvrx_jelly_probe_writer.h').exists())

    def test_opt_in_patches_once_and_preserves_synthesis_appearance(self):
        temp, vo, meson, writer = self.fixture()
        with temp:
            original = vo.read_text()
            self.assertTrue(PATCH.apply_patch(vo, meson, writer, True))
            patched = vo.read_text()
            self.assertIn('fongmi-jelly-probe-report-file', patched)
            self.assertIn('fongmi-jelly-probe-arm', patched)
            self.assertIn('#include <errno.h>', patched)
            self.assertIn('#include <math.h>', patched)
            self.assertIn('#include <string.h>', patched)
            self.assertIn('UPDATE_VIDEO', patched)
            self.assertIn('mp_motion_render_frames_probe', patched)
            self.assertIn('mp_motion_render_views_probe', patched)
            self.assertIn('mp_motion_render_probe', patched)
            self.assertIn('render_source_mix_callback_count++', patched)
            self.assertIn('motion_view_callback_count++', patched)
            self.assertIn('motion_view_pair_count++', patched)
            self.assertIn('motion_view_unsupported_pair_count++', patched)
            self.assertIn('motion_view_shader_callback_count++', patched)
            self.assertIn('motion_view_texture_callback_count++', patched)
            self.assertIn('motion_view_probe_dispatch_count++', patched)
            self.assertIn('mp_motion_create(p->gpu, 480)', patched)
            self.assertIn('jelly_probe_shader_view(mix, images);', patched)
            self.assertIn('jelly_probe_poll(p);', patched.split('static bool render_source_mix', 1)[1])
            self.assertIn('mp_motion_render_views_probe(\n        p->jelly_probe_motion', patched)
            self.assertIn('bool direct = frame->vsync_interval > 0 && !frame->redraw &&', patched)
            self.assertNotIn('bool direct = !probe_active', patched)
            self.assertNotIn('mp_motion_render_views_probe(mix->motion', patched)
            self.assertLess(
                patched.index('mix->result = mp_motion_render_views(mix->motion'),
                patched.index('mp_motion_render_views_probe(\n            p->jelly_probe_motion'),
            )
            self.assertIn('alpha >= 0.35f && alpha <= 0.65f', patched)
            self.assertIn('probe_pts_min <= 1.63 && probe_pts_max >= 1.46', patched)
            self.assertIn('fallback_probe_pair = !valid_probe_pts || !target_probe_pair', patched)
            self.assertIn('motion_render_callback_count++', patched)
            self.assertIn('armed_render_callback_count++', patched)
            self.assertIn('alpha_below_count++', patched)
            self.assertIn('alpha_above_count++', patched)
            self.assertIn('out_of_bounds_count++', patched)
            self.assertIn('mpi0->pts != MP_NOPTS_VALUE', patched)
            self.assertIn(': NAN', patched)
            self.assertIn('selection_mode = target_probe_pair ? 1 : 2', patched)
            self.assertIn('pixel_x = 1352, .pixel_y = 224', patched)
            self.assertIn('max_mapped_vram < MPVRX_JELLY_PROBE_RECORD_BYTES', patched)
            self.assertIn('.initial_data = zero_record', patched)
            self.assertIn('mpvrx_jelly_probe_writer_init(&p->jelly_probe_writer,', patched)
            self.assertIn('d->writer_init_status = MPVRX_JELLY_STAGE_OK', patched)
            self.assertIn('d->buffer_status = MPVRX_JELLY_STAGE_FAILED', patched)
            self.assertIn('pl_buf_poll(p->gpu, p->jelly_probe_buf, 0)', patched)
            self.assertIn('mpvrx_jelly_probe_record_is_activation', patched)
            self.assertIn('mpvrx_jelly_probe_record_was_evaluated', patched)
            self.assertIn('source_bounds_observed = 1', patched)
            self.assertIn('pixel_in_bounds = mpi0->params.w > 1352 && mpi0->params.h > 224', patched)
            self.assertIn('eligible_pair_count++', patched)
            self.assertIn('gpu_submit_count++', patched)
            self.assertIn('gpu_poll_count++', patched)
            self.assertIn('mpvrx_jelly_probe_writer_finish', patched)
            candidate_dispatch = patched.split('if (probe_pair && result == MP_MOTION_OK)', 1)[1].split(
                'if (probe_pair && ok)', 1
            )[0]
            self.assertNotIn('p->jelly_probe_armed = false;', candidate_dispatch)
            self.assertIn('jelly_probe_last_record_valid = evaluated;', patched)
            self.assertNotIn('pl_buf_read', patched)
            self.assertEqual(patched.count('pl_shader'), original.count('pl_shader'))
            self.assertEqual(patched.count('background_color'), original.count('background_color'))
            # The conditional must retain each existing synthesis call verbatim.
            self.assertIn('p->motion, &fp0->motion_frame, &fp1->motion_frame, alpha, output);', original)
            self.assertIn(': mp_motion_render_frames(p->motion, &fp0->motion_frame,\n                &fp1->motion_frame, alpha, output);', patched)
            meson_text = meson.read_text()
            self.assertEqual(meson_text.count("'video/out/mpvrx_jelly_probe_writer.c'"), 1)
            self.assertTrue((vo.parent / 'mpvrx_jelly_probe_writer.h').exists())
            once = patched, meson_text
            self.assertFalse(PATCH.apply_patch(vo, meson, writer, True))
            self.assertEqual(once, (vo.read_text(), meson.read_text()))

    def test_lifecycle_join_only_in_priv_destructor(self):
        temp, vo, meson, writer = self.fixture()
        with temp:
            PATCH.apply_patch(vo, meson, writer, True)
            source = vo.read_text()
            uninit = source.split('static void uninit(struct vo *vo)', 1)[1].split('static ', 1)[0]
            self.assertIn('pl_gpu_finish(p->gpu)', uninit)
            self.assertIn('mpvrx_jelly_probe_writer_capture', uninit)
            self.assertIn('mp_motion_destroy(&p->jelly_probe_motion)', uninit)
            self.assertIn('pl_buf_destroy', uninit)
            self.assertLess(uninit.index('pl_gpu_finish(p->gpu)'), uninit.index('mp_motion_destroy'))
            for forbidden in ('request_stop', '_join(', '_destroy(&p->jelly_probe_writer'):
                self.assertNotIn(forbidden, uninit)
            destructor = source.split('static void jelly_probe_priv_destroy', 1)[1].split('static void uninit', 1)[0]
            self.assertLess(destructor.index('jelly_probe_finish(p)'), destructor.index('_join'))
            self.assertIn('talloc_set_destructor(p, jelly_probe_priv_destroy)', source)

    def test_disarm_on_playback_stop_flushes_and_saves_last_skip(self):
        temp, vo, meson, writer = self.fixture()
        with temp:
            PATCH.apply_patch(vo, meson, writer, True)
            source = vo.read_text()
            stop_start = source.index('if (!p->next_opts->fongmi_jelly_probe_arm')
            stop = source[stop_start:stop_start + 1000]
            self.assertIn('p->jelly_probe_armed && p->jelly_probe_last_record_valid', stop)
            self.assertIn('pl_gpu_finish(p->gpu)', stop)
            self.assertIn('jelly_probe_poll(p)', stop)
            self.assertIn('mpvrx_jelly_probe_writer_capture', stop)
            self.assertLess(stop.index('pl_gpu_finish'), stop.index('jelly_probe_poll'))
            self.assertLess(stop.index('jelly_probe_poll'), stop.index('writer_capture'))
            self.assertIn('p->jelly_probe_armed = false;', stop)

    def test_strict_anchor_failure_does_not_write(self):
        temp, vo, meson, writer = self.fixture()
        with temp:
            vo.write_text(vo.read_text().replace('#include "gpu/utils.h"', '#include "gpu/changed.h"', 1))
            before = vo.read_bytes(), meson.read_bytes()
            with self.assertRaises(ValueError):
                PATCH.apply_patch(vo, meson, writer, True)
            self.assertEqual(before, (vo.read_bytes(), meson.read_bytes()))


if __name__ == '__main__':
    unittest.main()
