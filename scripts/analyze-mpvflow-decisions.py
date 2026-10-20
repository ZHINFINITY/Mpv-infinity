#!/usr/bin/env python3
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
# Copyright (c) 2026 ZHINFINITY. Mpv∞ Covered Code. See LICENSE.
"""Offline checks for paired MPVFlow decision-mask diagnostic ZIPs."""
from __future__ import annotations
import argparse, json, math, os, struct, sys, zipfile
from collections import Counter
from pathlib import Path

MAX_ZIP = 64 * 1024 * 1024
MAX_MANIFEST = 1024 * 1024
MAX_IMAGE = 8 * 1024 * 1024
MAX_PIXELS = 2_073_600
MAX_DIM = 1920
PALETTE = {0:(0,180,0),1:(128,128,128),2:(0,80,255),3:(255,0,0),4:(255,128,0),5:(0,255,255),6:(255,255,0),7:(255,0,255),8:(160,0,255),9:(128,64,0),10:(255,255,255)}
LEGACY_PALETTE = dict(PALETTE)
PALETTE.update({11:(0,128,255),12:(255,192,64)})
SOURCE_BLEND = {11,12}
FALLBACK = {3:0,7:0,9:0,4:1,8:1,10:1}
class InvalidArchive(ValueError): pass
def require(ok, message):
    if not ok: raise InvalidArchive(message)

def entry(z, name, cap=MAX_IMAGE):
    infos=[i for i in z.infolist() if i.filename==name]
    require(len(infos)==1, f"missing_or_duplicate_entry:{name}")
    info=infos[0]
    require(not info.is_dir() and info.file_size <= cap, f"entry_too_large:{name}")
    # Read bounded bytes and verify central-directory length.
    with z.open(info) as f: data=f.read(cap+1)
    require(len(data)==info.file_size and len(data)<=cap, f"entry_length_invalid:{name}")
    return data

def png(data, label):
    require(data[:8]==b'\x89PNG\r\n\x1a\n' and len(data)>=33, f"invalid_png:{label}")
    require(data[12:16]==b'IHDR', f"invalid_png_header:{label}")
    w,h,depth,ctype,comp,flt,inter=struct.unpack('>IIBBBBB',data[16:29])
    require(w>0 and h>0 and w<=MAX_DIM and h<=MAX_DIM and w*h<=MAX_PIXELS, f"invalid_geometry:{label}")
    require(depth==8 and ctype==6 and comp==0 and flt==0 and inter==0, f"png_must_be_noninterlaced_rgba8:{label}")
    try:
        from PIL import Image
        import io
        im=Image.open(io.BytesIO(data)); im.load()
        require(im.size==(w,h) and im.mode=='RGBA', f"invalid_png_pixels:{label}")
        return w,h,im
    except ImportError as e: raise InvalidArchive('Pillow is required to decode PNG entries') from e
    except InvalidArchive: raise
    except Exception as e: raise InvalidArchive(f"invalid_png_pixels:{label}:{e}") from e

def components(labels,w,h,allowed):
    # Connected-component statistics via bounded pixel flood fill. 4-connectivity.
    seen=bytearray(w*h); result=[]
    for i,v in enumerate(labels):
        if v not in allowed or seen[i]: continue
        seen[i]=1; stack=[i]; x0=x1=i%w; y0=y1=i//w; size=0
        while stack:
            p=stack.pop(); x=p%w; y=p//w; size+=1
            x0=min(x0,x); x1=max(x1,x); y0=min(y0,y); y1=max(y1,y)
            for q in (p-1 if x else -1,p+1 if x+1<w else -1,p-w if y else -1,p+w if y+1<h else -1):
                if q>=0 and not seen[q] and labels[q] in allowed: seen[q]=1; stack.append(q)
        result.append({'pixels':size,'bbox':[x0,y0,x1+1,y1+1],'width':x1-x0+1,'height':y1-y0+1})
    return result

def analyze(path, outdir=None):
    require(os.path.getsize(path)<=MAX_ZIP, 'zip_too_large')
    with zipfile.ZipFile(path) as z:
        names=[i.filename for i in z.infolist()]
        require(len(names)<=128, 'too_many_zip_entries')
        require(sum(i.file_size for i in z.infolist())<=MAX_ZIP+MAX_MANIFEST, 'expanded_archive_too_large')
        require(len(names)==len(set(names)), 'duplicate_zip_entries')
        mi=[i for i in z.infolist() if i.filename=='manifest.json']
        require(len(mi)==1 and mi[0].file_size<=MAX_MANIFEST, 'manifest_missing_or_too_large')
        manifest=json.loads(entry(z,'manifest.json',MAX_MANIFEST))
        require(manifest.get('decision_capture',{}).get('schema')=='mpvflow-decision-mask-v1','unsupported_decision_schema')
        require(manifest.get('paired_output_diagnostic') is True and manifest.get('pair_count')==1,'not_one_paired_diagnostic')
        w,h=manifest['output_dimensions']; aw,ah=manifest['analysis_dimensions']
        require(all(isinstance(n,int) and n>0 for n in (w,h,aw,ah)) and w<=MAX_DIM and h<=MAX_DIM and w*h<=MAX_PIXELS,'invalid_manifest_geometry')
        records=[r for r in manifest.get('records',[]) if r.get('pair_index')==0]
        by={(r.get('sample_index'),r.get('channel')):r for r in records}
        require(len(by)==len(records),'duplicate_manifest_image_record')
        required=[(-1,'source0'),(-1,'source1'),(0,'synthesized'),(1,'synthesized'),(0,'decision_raw'),(0,'decision_mask')]
        require(all(k in by for k in required),'required_image_record_missing')
        off, on, maskrec=(by[(0,'synthesized')],by[(1,'synthesized')],by[(0,'decision_mask')])
        require(off.get('variant')=='soft_blend_off' and on.get('variant')=='soft_blend_on','invalid_variant_order')
        def signature(r):
            p=r.get('pts',{})
            require(all(p.get(k) is not None for k in ('source0','source1','output')),'missing_pair_pts')
            return tuple(p[k] for k in ('source0','source1','output')), tuple(r.get('output_dimensions',[])), tuple(r.get('analysis_dimensions',[])), tuple(r.get('grid_dimensions',manifest.get('grid_dimensions',[]))), p.get('timestep')
        require(all(signature(by[k])==signature(off) for k in required),'off_on_mask_pts_or_geometry_mismatch')
        require(all((by[k].get('width'),by[k].get('height'))==(w,h) for k in required),'record_pixel_geometry_mismatch')
        require(signature(off)[1]==(w,h) and signature(off)[2]==(aw,ah),'manifest_record_geometry_mismatch')
        palette=manifest['decision_capture'].get('palette_rgb',{})
        if palette: require({int(k):tuple(v) for k,v in palette.items()} in (LEGACY_PALETTE,PALETTE),'invalid_manifest_palette')
        pts=off['pts']; alpha=float(manifest.get('paired_comparison',{}).get('alpha',0.5))
        require(math.isfinite(alpha) and abs(alpha-0.5)<1e-9,'comparison_alpha_must_be_midpoint')
        p0,p1,po=(float(pts[k]) for k in ('source0','source1','output'))
        require(all(math.isfinite(v) for v in (p0,p1,po)) and p1>p0 and abs(po-(p0+(p1-p0)*0.5))<1e-6 and pts.get('timestep')==0.5,'invalid_fixed_pair_timing')
        base='pair_0_sample_'
        def load(suffix):
            data=entry(z,base+suffix+'.png'); iw,ih,im=png(data,suffix)
            require((iw,ih)==(w,h),f"image_geometry_mismatch:{suffix}"); return im
        src0,src1,off,on,raw,mask=(load(s) for s in ('-1_source0','-1_source1','0_synthesized','1_synthesized','0_decision_raw','0_decision_mask'))
        rawrgba=list(raw.getdata()); maskp=list(mask.convert('RGB').getdata())
        rawp=[p[:3] for p in rawrgba]
        labels=[]; counts=Counter(); mismatches={0:0,1:0}; compared={0:0,1:0}; conflict=0
        blend_compared=0; blend_mismatches=0
        a0=list(src0.convert('RGB').getdata()); a1=list(src1.convert('RGB').getdata()); out=list(off.convert('RGB').getdata())
        for i,(r,m) in enumerate(zip(rawp,maskp)):
            bid=r[0]; require(bid in PALETTE,f"invalid_branch_id:{bid}"); require(rawrgba[i][3]==255,f"invalid_raw_alpha:{i}"); require(m==PALETTE[bid],f"mask_palette_mismatch:{i}")
            require(r[2]<=15,f'invalid_reason_bits:{i}')
            labels.append(bid); counts[bid]+=1
            if r[2]: conflict+=1
            if bid in FALLBACK:
                source=FALLBACK[bid]; expected=(a0 if source==0 else a1)[i]; compared[source]+=1
                if any(abs(out[i][c]-expected[c])>1 for c in range(3)): mismatches[source]+=1
            elif bid==1:
                source=0 if alpha<=0.5 else 1; expected=(a0 if source==0 else a1)[i]; compared[source]+=1
                if any(abs(out[i][c]-expected[c])>1 for c in range(3)): mismatches[source]+=1
            elif bid in SOURCE_BLEND:
                blend_compared+=1
                expected=[(1.0-alpha)*a0[i][c]+alpha*a1[i][c] for c in range(3)]
                if any(abs(out[i][c]-expected[c])>1 for c in range(3)): blend_mismatches+=1
        comps=components(labels,w,h,set(FALLBACK)|SOURCE_BLEND)
        for c in comps: c['cell_units']={'width':c['width']/(4*w/aw),'height':c['height']/(4*h/ah)}
        report={'engine':manifest.get('engine'),'geometry':{'output':[w,h],'analysis':[aw,ah],'grid':manifest.get('grid_dimensions'),'cell_footprint_output_pixels':[4*w/aw,4*h/ah]},'branch_counts':{str(k):counts[k] for k in PALETTE},'conflict_pixel_count':conflict,'source_equality':{str(s):{'compared_pixels':compared[s],'mismatch_pixels':mismatches[s],'tolerance_rgb':1} for s in (0,1)},'source_blend_equality':{'compared_pixels':blend_compared,'mismatch_pixels':blend_mismatches,'tolerance_rgb':1},'fallback_components':{'connectivity':4,'count':len(comps),'largest_pixels':max((c['pixels'] for c in comps),default=0),'largest_cell_units':max((max(c['cell_units'].values()) for c in comps),default=0),'regions':sorted(comps,key=lambda c:c['pixels'],reverse=True)[:128],'regions_shown_limit':128},'interpretation':'Mismatch counts are diagnostic comparisons, not assertions that captured artifacts are bad. Components larger than one nominal cell are multi-cell decision regions, not proof of a fixed output tile. This does not establish which regions are perceptually objectionable.'}
        if outdir:
            from PIL import Image
            overlay=Image.new('RGBA',(w,h)); pix=[]
            for i,b in enumerate(labels):
                if b in FALLBACK or b in SOURCE_BLEND: pix.append((255,40,200,150))
                elif rawp[i][2]: pix.append((255,220,0,170))
                else: pix.append((0,0,0,0))
                overlay.putdata(pix); outdir.mkdir(parents=True,exist_ok=True); overlay.save(outdir/'decision-overlay.png'); (outdir/'analysis.json').write_text(json.dumps(report,indent=2)+'\n')
        return report

def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('zipfile',type=Path); p.add_argument('--output-directory',type=Path); a=p.parse_args()
    try: print(json.dumps(analyze(a.zipfile,a.output_directory),indent=2))
    except (InvalidArchive,zipfile.BadZipFile,json.JSONDecodeError,KeyError,TypeError,ValueError) as e: print(json.dumps({'error':str(e)}),file=sys.stderr); return 2
    return 0
if __name__=='__main__': raise SystemExit(main())
