#!/usr/bin/env python3
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
# Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE.
"""Synthetic safety and correctness tests for analyze-mpvflow-decisions.py."""
import importlib.util, io, json, tempfile, unittest, zipfile
from pathlib import Path
from PIL import Image

SCRIPT=Path(__file__).resolve().parents[1]/'analyze-mpvflow-decisions.py'
spec=importlib.util.spec_from_file_location('decision_analysis',SCRIPT); mod=importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)

def png(rows):
    h=len(rows); w=len(rows[0]); im=Image.new('RGBA',(w,h)); im.putdata([p for row in rows for p in row]); b=io.BytesIO(); im.save(b,format='PNG'); return b.getvalue()

def make_zip(path, alter=False, bad_palette=False, bad_geometry=False, blend_id=None, legacy_palette=False):
    w,h=12,8
    src0=[[(20,30,40,255)]*w for _ in range(h)]; src1=[[(80,90,100,255)]*w for _ in range(h)]
    ids=[[0]*w for _ in range(h)]
    for y in range(1,7):
        for x in range(2,9): ids[y][x]=3 if blend_id is None else blend_id
    raw=[[(ids[y][x],128,0,255) for x in range(w)] for y in range(h)]
    mask=[[(mod.PALETTE[ids[y][x]][0],mod.PALETTE[ids[y][x]][1],mod.PALETTE[ids[y][x]][2],255) for x in range(w)] for y in range(h)]
    if bad_palette: mask[0][0]=(1,2,3,255)
    out=[row[:] for row in src0]
    if blend_id is not None:
        for y in range(1,7):
            for x in range(2,9): out[y][x]=(50,60,70,255)
    if alter: out[2][3]=(200,200,200,255)
    dims=[w+1,h] if bad_geometry else [w,h]
    meta={'paired_output_diagnostic':True,'pair_count':1,'engine':'synthetic','output_dimensions':[w,h],'analysis_dimensions':[w,h],'grid_dimensions':[3,2],'pts':{'source0':1.0,'source1':2.0,'output':1.5,'timestep':0.5},'paired_comparison':{'alpha':0.5},'decision_capture':{'schema':'mpvflow-decision-mask-v1'},'records':[]}
    meta['decision_capture']['palette_rgb']=mod.LEGACY_PALETTE if legacy_palette else mod.PALETTE
    channels=[(-1,'source0',src0),(-1,'source1',src1),(0,'synthesized',out),(1,'synthesized',src1),(0,'decision_raw',raw),(0,'decision_mask',mask)]
    names=[]
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
        for si,ch,rows in channels:
            name=f'pair_0_sample_{si}_{ch}.png'; names.append(name); z.writestr(name,png(rows))
            meta['records'].append({'pair_index':0,'sample_index':si,'channel':ch,'path':name,'width':dims[0],'height':dims[1],'output_dimensions':dims if bad_geometry and si==0 and ch=='decision_mask' else [w,h],'analysis_dimensions':[w,h],'pts':meta['pts'],'variant':'soft_blend_off' if si==0 and ch=='synthesized' else ('soft_blend_on' if si==1 and ch=='synthesized' else None)})
        z.writestr('manifest.json',json.dumps(meta))

class DecisionTests(unittest.TestCase):
    def test_source_fallback_and_component_larger_than_cell(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'x.zip'; make_zip(p); r=mod.analyze(p)
            self.assertEqual(r['source_equality']['0']['mismatch_pixels'],0)
            self.assertEqual(r['source_equality']['0']['compared_pixels'],42)
            c=r['fallback_components']['regions'][0]
            self.assertGreater(c['width'],4); self.assertGreater(c['height'],4)
            self.assertEqual(c['bbox'],[2,1,9,7])
    def test_altered_source_mismatch(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'x.zip'; make_zip(p,alter=True); r=mod.analyze(p)
            self.assertEqual(r['source_equality']['0']['mismatch_pixels'],1)
    def test_source_blends_are_not_endpoint_fallbacks(self):
        for branch in (11,12):
            with self.subTest(branch=branch), tempfile.TemporaryDirectory() as d:
                p=Path(d)/'x.zip'; make_zip(p,blend_id=branch); r=mod.analyze(p)
                self.assertEqual(r['source_equality']['0']['compared_pixels'],0)
                self.assertEqual(r['source_blend_equality']['compared_pixels'],42)
                self.assertEqual(r['source_blend_equality']['mismatch_pixels'],0)
                self.assertEqual(r['branch_counts'][str(branch)],42)
                self.assertEqual(r['fallback_components']['regions'][0]['pixels'],42)
    def test_altered_blend_is_detected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'x.zip'; make_zip(p,blend_id=11,alter=True); r=mod.analyze(p)
            self.assertEqual(r['source_blend_equality']['mismatch_pixels'],1)
    def test_legacy_capture_palette_is_supported(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'x.zip'; make_zip(p,legacy_palette=True); r=mod.analyze(p)
            self.assertEqual(r['source_equality']['0']['compared_pixels'],42)
    def test_invalid_palette_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'x.zip'; make_zip(p,bad_palette=True)
            with self.assertRaises(mod.InvalidArchive): mod.analyze(p)
    def test_invalid_geometry_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'x.zip'; make_zip(p,bad_geometry=True)
            with self.assertRaises(mod.InvalidArchive): mod.analyze(p)

if __name__=='__main__': unittest.main()
