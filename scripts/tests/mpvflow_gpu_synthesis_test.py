#!/usr/bin/env python3
"""Execute the production synthesis shader, full-frame fixtures, and negative controls."""
from __future__ import annotations
import argparse
import ast
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "app/src/main/cpp/mpvflow/mpvflow_gpu.c"
HARNESS = ROOT / "scripts/tests/mpvflow_gpu_synthesis_test.c"


def require_tooling(required: bool) -> bool:
    missing = []
    for command in ("glslangValidator", "cc"):
        if not shutil.which(command):
            missing.append(command)
    if not Path("/usr/include/vulkan/vulkan.h").is_file():
        missing.append("Vulkan development headers")
    if not any(Path("/usr/lib").glob("**/libvulkan.so")):
        missing.append("Vulkan loader/development library")
    if required and not list(Path("/usr/share/vulkan/icd.d").glob("lvp_icd*.json")):
        missing.append("lavapipe Vulkan software driver")
    if missing:
        message = "Vulkan synthesis pixel oracle unavailable: " + ", ".join(missing)
        if required:
            raise SystemExit(message)
        print(message + "; skipping optional local run")
        return False
    return True


def embedded_synthesis_shader(source: str | None = None) -> str:
    if source is None:
        source = SOURCE.read_text(encoding="utf-8")
    match = re.search(r"static const char shader_synthesize\[\] =\n(.*?);\n", source, re.DOTALL)
    if not match:
        raise SystemExit("Could not find embedded shader_synthesize")
    return "".join(ast.literal_eval(literal) for literal in re.findall(r'"(?:\\.|[^"\\])*"', match.group(1)))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-vulkan", action="store_true")
    parser.add_argument("--decision-mode", action="store_true")
    parser.add_argument("--soft-blend-baseline-revision", help="prove Soft Blend threshold fixtures fail this git revision")
    parser.add_argument("--baseline-revision", help="also prove the full-frame regressions fail this git revision")
    parser.add_argument("--aperture-baseline-revision", help="prove the tied-outline regression fails this git revision")
    parser.add_argument("--inverse-baseline-revision", help="prove affine camera inverse regressions fail this git revision")
    args = parser.parse_args()
    if not require_tooling(args.require_vulkan):
        return
    with tempfile.TemporaryDirectory(prefix="mpvflow-synthesis-oracle-") as temp:
        directory = Path(temp)
        shader_source = embedded_synthesis_shader()
        centered_origin = "vec2 center=p-vec2(2.0);"
        if shader_source.count(centered_origin) != 1:
            raise SystemExit("Centered flow-grid origin mapping is missing or duplicated")
        if "softBlend&&confidence<0.28" in shader_source:
            raise SystemExit("Soft Blend must not retain the binary confidence gate")
        for anchor in (
            "vec4 visibilityWarped=(ca*weight0+cb*weight1+fallback*max(0.0001-total,0.0))/max(total,0.0001)",
            "float warpTrust=smoothstep(0.0,0.60,confidence)",
            "float softWarpTrust=smoothstep(0.0,0.60,confidence)*boundaryTrust",
        ):
            if shader_source.count(anchor) < 1:
                raise SystemExit(f"Continuous Soft Blend anchor missing: {anchor}")

        def compile_variant(text: str, label: str) -> Path:
            source = directory / f"{label}.comp"
            spirv = directory / f"{label}.spv"
            source.write_text(text, encoding="utf-8")
            result = subprocess.run(["glslangValidator", "-V", "--target-env", "vulkan1.2", "-S", "comp", "-o", str(spirv), str(source)], capture_output=True, text=True)
            if result.returncode:
                raise SystemExit(f"Shader compilation failed ({label}):\n" + result.stdout + result.stderr)
            return spirv

        def compile_harness(large: bool, square: bool = False) -> Path:
            output = directory / ("affine_camera_oracle" if square else "full_frame_oracle" if large else "odd_extent_oracle")
            definitions = ["-DFLOW_LARGE_FIXTURES", "-DWIDTH=64", f"-DHEIGHT={64 if square else 32}", "-DANALYSIS_WIDTH=16", f"-DANALYSIS_HEIGHT={16 if square else 8}"] if large else []
            result = subprocess.run(["cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", *definitions, str(HARNESS), "-lvulkan", "-lm", "-o", str(output)], capture_output=True, text=True)
            if result.returncode:
                raise SystemExit("Vulkan pixel harness compilation failed:\n" + result.stdout + result.stderr)
            return output

        normal = compile_harness(False)
        large = compile_harness(True)
        affine = compile_harness(True, square=True)
        environment = os.environ.copy()
        icds = sorted(Path("/usr/share/vulkan/icd.d").glob("lvp_icd*.json"))
        if icds:
            environment["VK_ICD_FILENAMES"] = str(icds[0])

        def run(program: Path, spirv: Path, flags: tuple[str, ...], label: str, failure: str | None = None) -> None:
            result = subprocess.run([str(program), str(spirv), *flags], capture_output=True, text=True, env=environment)
            if failure is None:
                if result.returncode:
                    raise SystemExit(f"Pixel oracle failed ({label}):\n" + result.stdout + result.stderr)
                print(f"[{label}]\n" + result.stdout, end="")
            else:
                if result.returncode == 0 or failure not in result.stderr:
                    raise SystemExit(f"Negative control did not fail for the expected reason ({label}):\n" + result.stdout + result.stderr)
                print(f"[negative control caught: {label}]\n" + result.stderr, end="")

        production = compile_variant(shader_source, "production")
        for flags, label in ((("0", "0"), "default-off production"), (("1", "0"), "opt-in soft blend"), (("1", "1"), "opt-in continuous soft blend")):
            run(normal, production, flags, label)
        if args.decision_mode:
            decision_shader = shader_source.replace("#version 450\n", "#version 450\n#define MPVFLOW_CAPTURE_DECISIONS 1\n", 1)
            run(normal, compile_variant(decision_shader, "decisions"), ("0", "0", "1"), "decision-mask capture")
        run(large, production, ("0", "0"), "whole-frame 4x upscaling / 32px motion")
        run(large, production, ("0", "0", "0", "bounds"), "constant-translation smooth support boundaries")

        for flags in (("0", "0"), ("1", "0"), ("1", "1")):
            run(affine, production, (*flags, "0", "affine"), f"invertible camera zoom/rotation soft={flags}")
            run(large, production, (*flags, "0", "coherent"), f"coherent moving outlines soft={flags}")
            run(large, production, (*flags, "0", "occlusion"), f"independent foreground and fast camera pan soft={flags}")
        # Deliberately force both synthesis branches to the ordinary temporal
        # source blend. The occlusion oracle must reject this (old/new red
        # ghosts), rather than merely checking that output is nonuniform.
        source_only = shader_source
        soft_output = "outc=FLOW_RESULT(mix(fallback,visibilityWarped,softWarpTrust),boundaryConflict?12:0,confidence,boundaryConflict?captureReason:0);"
        source_output = "outc=FLOW_RESULT(mix(texelFetch(frame0,p,0),texelFetch(frame1,p,0),pc.timestep),0,confidence,0);"
        if source_only.count(soft_output) != 1 or source_only.count("outc=warped;") != 1:
            raise SystemExit("Source-only occlusion mutant anchors are missing or duplicated")
        source_only = source_only.replace(soft_output, source_output, 1).replace(
            "outc=warped;", "outc=mix(texelFetch(frame0,p,0),texelFetch(frame1,p,0),pc.timestep);", 1)
        source_only_spirv = compile_variant(source_only, "source_only_occlusion")
        run(large, source_only_spirv, ("0", "0", "0", "occlusion"),
            "source-only occlusion negative control", "Moving-foreground")
        run(large, source_only_spirv, ("1", "1", "0", "occlusion"),
            "source-only Soft Blend occlusion negative control", "Moving-foreground")
        aperture_guard = "if(cycleSamplesFinite&&min(f.w,b.w)<0.30"
        if shader_source.count(aperture_guard) != 1:
            raise SystemExit("Shared coherent-motion validation anchor missing or duplicated")
        mutant = shader_source.replace(aperture_guard, "if(!softBlend&&cycleSamplesFinite&&min(f.w,b.w)<0.30", 1)
        run(large, compile_variant(mutant, "soft_aperture_disabled"), ("1", "1", "0", "coherent"),
            "disabled Soft Blend coherent support", "Aperture outline regression:")
        if args.inverse_baseline_revision:
            old_source = subprocess.check_output(
                ["git", "show", f"{args.inverse_baseline_revision}:app/src/main/cpp/mpvflow/mpvflow_gpu.c"],
                cwd=ROOT, text=True)
            old = compile_variant(embedded_synthesis_shader(old_source), "inverse_baseline")
            for flags in (("0", "0"), ("1", "1")):
                run(affine, old, (*flags, "0", "affine"), f"baseline inverse solve soft={flags}", "Affine inverse regression:")

        run(large, production, ("0", "0", "0", "aperture"), "coherent aperture-tied outlines and strict rejection")
        aperture_mutations = (
            (("float aperture=apertureQuality(", "float aperture=0.0*apertureQuality("),
             "disabled aperture support", "fixture=tied straight outline"),
            (("max(f.z,b.z)<0.025", "true"),
             ("(1.0-smoothstep(0.005,0.025,residual))", "1.0"),
             "disabled aperture residual check", "fixture=tied high-residual"),
            (("max(cycleError0,cycleError1)<0.75", "true"),
             ("(1.0-smoothstep(0.25,0.75,cycle))", "1.0"),
             "disabled aperture cycle check", "fixture=tied but cycle-inconsistent"),
            (("(1.0-smoothstep(0.01,0.04,colorError))", "1.0"),
             "disabled aperture full-resolution color check", "fixture=tied full-resolution color conflict"),
        )
        for *replacements, label, failure in aperture_mutations:
            mutant = shader_source
            for needle, replacement in replacements:
                if mutant.count(needle) != 1:
                    raise SystemExit(f"Aperture mutation anchor missing or duplicated: {needle}")
                mutant = mutant.replace(needle, replacement, 1)
            run(large, compile_variant(mutant, label.replace(" ", "_")),
                ("0", "0", "0", "aperture"), label, failure)
        if args.aperture_baseline_revision:
            old_source = subprocess.check_output(
                ["git", "show", f"{args.aperture_baseline_revision}:app/src/main/cpp/mpvflow/mpvflow_gpu.c"],
                cwd=ROOT, text=True)
            old = compile_variant(embedded_synthesis_shader(old_source), "aperture_baseline")
            run(large, old, ("0", "0", "0", "aperture"),
                "last-tested commit tied-outline regression", "fixture=tied straight outline")
        # This is deliberately separate from --baseline-revision: it mutates
        # only pointInBounds and therefore proves this regression, rather than
        # relying on an unrelated historical revision's fixture set.
        bounds_helper = "float pointInBounds(vec2 p,ivec2 sz){ float margin=min(min(p.x,p.y),min(float(sz.x-1)-p.x,float(sz.y-1)-p.y)); return smoothstep(0.0,1.0,margin); }"
        if shader_source.count(bounds_helper) != 1:
            raise SystemExit("Smooth pointInBounds anchor is missing or duplicated")
        hard_bounds = shader_source.replace(
            bounds_helper,
            "float pointInBounds(vec2 p,ivec2 sz){ return step(0.0,p.x)*step(p.x,float(sz.x-1))*step(0.0,p.y)*step(p.y,float(sz.y-1)); }",
            1,
        )
        # Always run this mutation: the exact stripe oracle must fail the old
        # binary gate, proving the regression test is not merely checking for
        # absence of a large jump.
        run(large, compile_variant(hard_bounds, "hard_bounds_baseline"), ("0", "0", "0", "bounds"), "old hard bounds gate", "Exact stripe oracle mismatch")

        def field_shader(text: str) -> str:
            # Execute the ACTUAL helper, not a Python reconstruction. Changing
            # confidence must not move coordinates or expose the coarse lattice.
            text = text.replace("void main(){ FLOW_BEGIN", "void unusedSynthesisMain(){ FLOW_BEGIN", 1)
            return text + "\nvoid main(){ ivec2 p=ivec2(gl_GlobalInvocationID.xy),sz=imageSize(outTex); if(any(greaterThanEqual(p,sz)))return; vec2 q=clamp((vec2(p)+0.5)*vec2(pc.cfg.zw)/vec2(sz)-0.5,vec2(0.0),vec2(pc.cfg.zw-1)); float v=flowAtBilinear(q,0).x/8.0; imageStore(outTex,p,vec4(v,v,v,1.0)); }\n"

        run(large, compile_variant(field_shader(shader_source), "continuous_field"), ("0", "0", "0", "field"), "continuous field across asymmetric grid confidence")
        inverse_loop = re.search(r"for\(int i=0;i<2;i\+\+\)\{ mat2 dfA,dfZ;.*? \} f=flowAtBilinear\(a,0\);", shader_source)
        if not inverse_loop:
            raise SystemExit("Bounded Jacobian inverse-loop anchor is missing")
        fixed_point = shader_source[:inverse_loop.start()] + "for(int i=0;i<2;i++){ f=flowAtBilinear(a,0); a=flowP-pc.timestep*f.xy; b=flowAtBilinear(z,1); z=flowP-(1.0-pc.timestep)*b.xy; } f=flowAtBilinear(a,0);" + shader_source[inverse_loop.end():]
        fixed_spirv = compile_variant(fixed_point, "fixed_point_inverse")
        run(normal, fixed_spirv, ("0", "0", "0", "residual"), "residual guard with deliberately nonconverged inverse")
        for flags in (("0", "0"), ("1", "1")):
            run(affine, fixed_spirv, (*flags, "0", "affine"), "disabled Jacobian inverse refinement", "Affine inverse regression:")
        guard = "float inverseResidual0Valid=1.0-smoothstep(0.25,0.75,inverseResidual0), inverseResidual1Valid=1.0-smoothstep(0.25,0.75,inverseResidual1);"
        if shader_source.count(guard) != 1:
            raise SystemExit("Inverse residual guard mutation anchor is missing")
        mutant = fixed_point.replace(guard, "float inverseResidual0Valid=1.0, inverseResidual1Valid=1.0;", 1)
        run(normal, compile_variant(mutant, "unguarded_inverse"), ("0", "0", "0", "residual"), "disabled inverse guard", "fixture=nonconverged inverse warp")
        geometric = "return mix(mix(v00,v10,t.x),mix(v01,v11,t.x),t.y)"
        if shader_source.count(geometric) != 2:
            raise SystemExit("Pure geometric bilinear field is missing")
        for needle, replacement, label in ((geometric, "return v00", "nearest lattice field"), (centered_origin, "vec2 center=p;", "uncentered lattice field")):
            mutant = shader_source.replace(needle, replacement, 1)
            run(large, compile_variant(field_shader(mutant), label.replace(" ", "_")), ("0", "0", "0", "field"), label, "fixture=confidence-independent continuous geometric field")
        common = "confidence=softBlend?max(confidence0,confidence1):min(confidence0,confidence1)"
        if shader_source.count(common) != 1:
            raise SystemExit("Common-support trust anchor is missing")
        mutant = shader_source.replace(common, "confidence=max(confidence0,confidence1)", 1)
        run(large, compile_variant(mutant, "one_sided_trust"), ("0", "0"), "one-sided warp trust", "fixture=unsupported reverse motion")
        fallback = "vec4 c=mix(texelFetch(frame0,p,0),texelFetch(frame1,p,0),pc.timestep);"
        mutant = shader_source.replace(fallback, "vec4 c=pc.timestep<0.5?texelFetch(frame0,p,0):texelFetch(frame1,p,0);")
        run(large, compile_variant(mutant, "endpoint_nan_fallback"), ("0", "0", "0", "nan"), "hard endpoint nonfinite fallback", "fixture=nonfinite motion")
        for replacement, label, proof in (("step(0.28,confidence)*boundaryTrust", "hard_confidence", "Hard confidence contour seam"), ("smoothstep(0.0,0.60,confidence)", "hard_boundary", "Hard boundary contour seam")):
            mutant = shader_source.replace("float warpTrust=smoothstep(0.0,0.60,confidence)*boundaryTrust", "float warpTrust=" + replacement, 1)
            run(normal, compile_variant(mutant, label), ("0", "0"), label, proof)
        # Restore each old Soft Blend discontinuity independently. The production
        # flags (soft=true, preserve=true) must catch both, not just compile them.
        soft_trust = "float softWarpTrust=smoothstep(0.0,0.60,confidence)*boundaryTrust"
        for replacement, label, proof in (
            ("step(0.28,confidence)*boundaryTrust", "soft_hard_confidence", "Hard confidence contour seam"),
            ("smoothstep(0.0,0.60,confidence)*(boundaryConflict?0.0:1.0)", "soft_hard_boundary", "Hard boundary contour seam"),
        ):
            mutant = shader_source.replace(soft_trust, "float softWarpTrust=" + replacement, 1)
            run(normal, compile_variant(mutant, label), ("1", "1", "0", "seams"), label, proof)
        if args.soft_blend_baseline_revision:
            old_source = subprocess.check_output(["git", "show", f"{args.soft_blend_baseline_revision}:app/src/main/cpp/mpvflow/mpvflow_gpu.c"], cwd=ROOT, text=True)
            old = compile_variant(embedded_synthesis_shader(old_source), "soft_baseline")
            # The default policy must already pass: this isolates the active Soft
            # Blend regression from earlier, unrelated motion-pipeline defects.
            run(normal, old, ("0", "0", "0", "seams"), "baseline default contours")
            for flags in (("1", "0", "0", "seams"), ("1", "1", "0", "seams")):
                run(normal, old, flags, "baseline Soft Blend contours", "Hard confidence contour seam")
        if args.baseline_revision:
            old_source = subprocess.check_output(["git", "show", f"{args.baseline_revision}:app/src/main/cpp/mpvflow/mpvflow_gpu.c"], cwd=ROOT, text=True)
            old_shader = embedded_synthesis_shader(old_source)
            old = compile_variant(old_shader, "baseline")
            run(large, old, ("0", "0"), "baseline unsupported reverse motion", "fixture=unsupported reverse motion")
            run(large, old, ("0", "0", "0", "nan"), "baseline nonfinite fallback", "fixture=nonfinite motion")
            run(large, compile_variant(field_shader(old_shader), "baseline_field"), ("0", "0", "0", "field"), "baseline confidence-selected coordinates", "fixture=confidence-independent continuous geometric field")


if __name__ == "__main__":
    main()
