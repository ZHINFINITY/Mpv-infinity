#!/usr/bin/env python3
"""Static checks for the coordinated native FongMi MPV runtime upgrade."""
import hashlib
import importlib.util
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PREPARE_SPEC = importlib.util.spec_from_file_location(
    "prepare_mpvflow_contract", ROOT / "scripts/prepare-mpvflow.py"
)
assert PREPARE_SPEC is not None and PREPARE_SPEC.loader is not None
PREPARE_MODULE = importlib.util.module_from_spec(PREPARE_SPEC)
PREPARE_SPEC.loader.exec_module(PREPARE_MODULE)
PREPARE = (ROOT / "scripts/prepare-mpvflow.py").read_text(encoding="utf-8")
BUILD = (ROOT / "scripts/build-mpvflow-mpv-runtime.sh").read_text(encoding="utf-8")
VULKAN_HEADERS = (ROOT / "scripts/prepare-mpvflow-vulkan-headers.py").read_text(encoding="utf-8")
WORKFLOWS = [
    (ROOT / ".github/workflows/debug-apk.yml").read_text(encoding="utf-8"),
    (ROOT / ".github/workflows/mpvflow-debug-arm64.yml").read_text(encoding="utf-8"),
    (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8"),
]
MANIFEST = (ROOT / "THIRD_PARTY_SOURCE.md").read_text(encoding="utf-8")
PACKAGED = (ROOT / "app/src/main/assets/licenses/third-party-source.md").read_text(encoding="utf-8")
LICENSES = (ROOT / "app/src/main/assets/mpv/licenses/README.md").read_text(encoding="utf-8")
GPL_LICENSE = ROOT / "app/src/main/assets/mpv/licenses/LICENSE.GPL"
MPV_VIEW = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/player/MPVView.kt").read_text(encoding="utf-8")
ABOUT = (ROOT / "app/src/main/java/app/infinity/mpvz/ui/preferences/AboutScreen.kt").read_text(encoding="utf-8")

MPV_SHA = "14b3999a1a3e322c217bc90ee4df5a8f1bc88f69"
BUILDER_SHA = "1efc82739f6f7034cd63e7ee8b3ec5431df27467"
PLACEBO_SHA = "b89c10e1bdd5d7742ca576ebad94a906ddca91e8"
GPL_SHA = "edaef632cbb643e4e7a221717a6c441a4c1a7c918e6e4d56debc3d8739b233f6"
MPVLIB_ANDROID_SHA = "d04217abe8e72e4a099269f045855bf69b87d53b"
FFMPEG_SHA = "f2c5162b7c4b3e8835de270ff78cecc524decad1"
VULKAN_HEADERS_SHA = "a4f8ada9f4f97c45b8c89c57997be9cebaae65d2"
PATCH_DIR = ROOT / "scripts/patches/mpvlibandroid-fongmi"
PATCHER = (PATCH_DIR / "patch-libbluray.sh").read_text(encoding="utf-8")
PATCH_README = (PATCH_DIR / "README.md").read_text(encoding="utf-8")
MOTION_PATCHER = (ROOT / "scripts/patch_fongmi_motion_artifact.py").read_text(encoding="utf-8")
MOTION_DIAGNOSTICS = (ROOT / "scripts/patch_fongmi_motion_diagnostics.py").read_text(encoding="utf-8")
VO_DIAGNOSTICS = (ROOT / "scripts/patch_fongmi_vo_diagnostics.py").read_text(encoding="utf-8")
MOTION_SNAPSHOT = (ROOT / "app/src/main/cpp/mpvflow/fongmi/motion.c").read_text(encoding="utf-8")
FONGMI_GPU_TEST = (ROOT / "app/src/main/cpp/mpvflow/fongmi/test/gpu_motion.c").read_text(encoding="utf-8")
PATCH_HASHES = {
    "libbluray-1.4.1-still-eof.patch": "cc0a15ee88b1ead665dadbc3ac363c2686e5184fc1e04a76713665cad57c0fc7",
    "libbluray-1.4.1-checked-seek.patch": "1055d99f0940b3077e9f7171a220f242396e6bf5196415f3fc987b316d51447c",
    "libbluray-1.4.1-menu-info.patch": "17a1b88c72c3b467ba6ac8d9419f003c93f694733b9214deee13bad5ebfedb5c",
    "libbluray-1.4.1-bdj-pointer.patch": "232ce1e634335af5d69a705745a57ea5cd5da8d7160b9441b5ba154e7b3dec85",
}

assert "FONGMI_PATCH" not in PREPARE
assert PREPARE.count("subprocess.run([sys.executable, str(patcher), str(mpv_dir)], cwd=ROOT, check=True)") == 1
assert "scripts/patch_fongmi_motion_diagnostics.py" in PREPARE
assert "scripts/patch_fongmi_vo_diagnostics.py" in PREPARE
for diagnostic_failure in (
    '"source_dimensions_out_of_range"',
    '"texture_unavailable"',
    '"dispatch_failed"',
    "diagnostic_failure_stage",
    "diagnostic_failed_plane",
):
    assert diagnostic_failure in MOTION_DIAGNOSTICS, diagnostic_failure
assert ".failure_stage = m->diagnostic_failure_stage" in MOTION_DIAGNOSTICS
assert ".failed_plane = m->diagnostic_failed_plane" in MOTION_DIAGNOSTICS
assert "capture.failure_stage" in VO_DIAGNOSTICS
assert "capture.failed_plane" in VO_DIAGNOSTICS
assert "capture.width, capture.height" in VO_DIAGNOSTICS
assert 'debug_capture_enabled = os.environ.get("MPVFLOW_DEBUG_CAPTURE") == "1"' in PREPARE
assert "if debug_capture_enabled:" in PREPARE
assert 'if not debug_capture_enabled and any(marker in capture_source for marker in capture_markers)' in PREPARE
assert "MPVFLOW_DEBUG_CAPTURE: '1'" in WORKFLOWS[1]
assert "-PmpvFlowFongmiDiagnosticCapture=true" in WORKFLOWS[1]
assert "fongmi-diagnostic-capture-v1" in WORKFLOWS[1]
assert all("MPVFLOW_DEBUG_CAPTURE" not in workflow for i, workflow in enumerate(WORKFLOWS) if i != 1)
assert all("mpvFlowFongmiDiagnosticCapture=true" not in workflow for i, workflow in enumerate(WORKFLOWS) if i != 1)
assert all("fongmi-diagnostic-capture-v1" not in workflow for i, workflow in enumerate(WORKFLOWS) if i != 1)
assert "shutil.copy" not in PREPARE
for marker in (
    "version: '>=7.387.0'",
    "video/out/gpu_next/motion.c",
    "libmpv_motion = static_library('mpv-motion'",
    "video/out/hwdec/hwdec_aimagereader.c",
    "video/out/hwdec/hwdec_aimagereader_vk_direct.c",
    "mp_motion_prepare_view",
    "mp_motion_render_views",
):
    assert marker in PREPARE, marker
assert '{"motion-interpolation-info", mp_property_motion_info}' in PREPARE
assert 'mp.get_property_native("motion-interpolation-info")' in PREPARE
assert "or not has_source_visibility_output_contract(motion)" in PREPARE
assert PREPARE_MODULE.has_source_visibility_output_contract(
    "return source_blend * (1.0 - visibility_sum) +\n"
    "       warped0 * visibility.x + warped1 * visibility.y;"
)
assert PREPARE_MODULE.has_source_visibility_output_contract(
    "float applied_trust = clamp(visibility_sum, 0.0, 1.0);\n"
    "vec4 final_output = source_blend * (1.0 - applied_trust) +\n"
    "                    warped0 * visibility.x + warped1 * visibility.y;"
)
assert not PREPARE_MODULE.has_source_visibility_output_contract(
    "return mix(source_blend, warped_mix, clamp(trust, 0.0, 1.0));"
)
assert PLACEBO_SHA in BUILD
assert 'https://github.com/FongMi/libplacebo.git' in BUILD
assert f'libplacebo_revision = "{PLACEBO_SHA}"' in BUILD
assert 'git -C libplacebo remote set-url origin https://github.com/FongMi/libplacebo.git' in BUILD
assert 'git -C libplacebo fetch --depth=1 origin {libplacebo_revision}' in BUILD
assert 'git -C libplacebo checkout --detach FETCH_HEAD' in BUILD
assert 'libplacebo_actual" != "{libplacebo_revision}"' in BUILD
assert FFMPEG_SHA in BUILD
assert 'ffmpeg_version_suffix = f"mpvflow-fongmi-{ffmpeg_revision}"' in BUILD
assert 'EXPECTED_FFMPEG_REPOSITORY="https://github.com/FongMi/FFmpeg.git"' in BUILD
assert 'git -C ffmpeg fetch --depth=1 origin {ffmpeg_revision}' in BUILD
assert 'prepare-mpvflow-vulkan-headers.py' in BUILD
assert f'VULKAN_HEADERS_REVISION = "{VULKAN_HEADERS_SHA}"' in VULKAN_HEADERS
assert 'VULKAN_HEADERS_REPOSITORY = "https://github.com/KhronosGroup/Vulkan-Headers.git"' in VULKAN_HEADERS
for symbol in (
    "VK_EXT_SHADER_REPLICATED_COMPOSITES_EXTENSION_NAME",
    "VK_KHR_SHADER_EXPECT_ASSUME_EXTENSION_NAME",
    "VK_KHR_SHADER_MAXIMAL_RECONVERGENCE_EXTENSION_NAME",
    "VK_KHR_SHADER_RELAXED_EXTENDED_INSTRUCTION_EXTENSION_NAME",
    "VK_KHR_SHADER_SUBGROUP_ROTATE_EXTENSION_NAME",
    "VK_KHR_VIDEO_DECODE_AV1_EXTENSION_NAME",
    "VK_KHR_VIDEO_MAINTENANCE_2_EXTENSION_NAME",
    "VkPhysicalDeviceVideoMaintenance2FeaturesKHR",
    "VkPhysicalDeviceShaderRelaxedExtendedInstructionFeaturesKHR",
):
    assert symbol in VULKAN_HEADERS, f"Vulkan header staging must require {symbol}"
assert '"vulkan", "vk_video"' in VULKAN_HEADERS
assert VULKAN_HEADERS.count("verify_clean_checkout(source_dir)") == 2
assert '"--ignored=matching"' in VULKAN_HEADERS
assert "reject_symlinks(include_dir" in VULKAN_HEADERS
assert 'ensure_real_directory(destination, "Android arm64 include prefix")' in VULKAN_HEADERS
assert f'EXPECTED_MPV_REVISION="{MPV_SHA}"' in BUILD
assert f'EXPECTED_BUILDER_REVISION="{BUILDER_SHA}"' in BUILD
assert 'git -C "$MPV_SOURCE_DIR" rev-parse HEAD' in BUILD
assert 'git -C "$MPV_BUILDER_DIR" rev-parse HEAD' in BUILD
motion_patch_call = 'python3 "$MPVFLOW_ROOT/scripts/patch_fongmi_motion_artifact.py" deps/mpv\\n'
assert motion_patch_call in BUILD
assert BUILD.index("motion_artifact_patch + flow_prepare") > BUILD.index("motion_artifact_patch = ")
assert "MPVFLOW_ARTIFACT_GUARDS_V4" in PREPARE
assert "MPVFLOW_WARP_TRAJECTORY_COHERENCE_V1" in PREPARE
assert "MPVFLOW_WARP_PRIMARY_TRUST_CAP_V1" in PREPARE
assert 'or "MPVFLOW_WARP_TRAJECTORY_TRUST_V2" in motion' in PREPARE
assert 'or "MPVFLOW_INSHOT_NEAREST_SOURCE_FALLBACK_V1" in motion' in PREPARE
assert "MPVFLOW_SCENE_CUT_SPATIAL_V1" in PREPARE
assert "MPVFLOW_FINE_MOTION_GRID_V1" in PREPARE
assert "MPVFLOW_HIGH_RES_FINE_GRID_V1" in PREPARE
assert "MPVFLOW_REFINEMENT_APERTURE_NORMAL_V1" in PREPARE
assert "MPVFLOW_SPATIAL_TRUST_DOWNWARD_CAP_V3" in PREPARE
assert "MPVFLOW_SPATIAL_MEDOID_FALLBACK_V1" in PREPARE
assert "int spacing = (level == LEVELS - 1 || level == 0) ? 4 : 8;" in PREPARE
assert "MPVFLOW_ARTIFACT_TRUST_TAPER_V2" not in PREPARE
assert "MPVFLOW_ARTIFACT_GUARDS_V4" in MOTION_PATCHER
assert "MPVFLOW_WARP_TRAJECTORY_COHERENCE_V1" in MOTION_PATCHER
assert "MPVFLOW_WARP_PRIMARY_TRUST_CAP_V1" in MOTION_PATCHER
assert "REJECTED_WARP_TRUST_MARKER" in MOTION_PATCHER
assert "REJECTED_INSHOT_FALLBACK_MARKER" in MOTION_PATCHER
assert "MPVFLOW_SCENE_CUT_SPATIAL_V1" in MOTION_PATCHER
assert "MPVFLOW_FINE_MOTION_GRID_V1" in MOTION_PATCHER
assert "MPVFLOW_REFINEMENT_APERTURE_NORMAL_V1" in MOTION_PATCHER
assert "MPVFLOW_SPATIAL_TRUST_DOWNWARD_CAP_V3" in MOTION_PATCHER
assert "MPVFLOW_SPATIAL_MEDOID_FALLBACK_V1" in MOTION_PATCHER
assert "if (total < 0.25)\n        return -1.0;" in MOTION_PATCHER
assert "MPVFLOW_HIGH_RES_FINE_GRID_V1" in MOTION_PATCHER
assert "if (level == 0 && scale < 0.333334)" in MOTION_PATCHER
assert "float spatial_island = max(0.0, clamp(trust, 0.0, 1.0) - spatial_consensus);" in MOTION_PATCHER
assert "float spatial_guard_weight = 0.75 * boundary_activation *" in MOTION_PATCHER
assert "trust = mix(trust, min(trust, spatial_consensus)," in MOTION_PATCHER
assert "trust = mix(trust, spatial_consensus," not in MOTION_PATCHER
assert "if (spatial_consensus >= 0.0) {" in MOTION_PATCHER
assert "int spacing = (level == LEVELS - 1 || level == 0) ? 4 : 8;" in MOTION_PATCHER
assert "MPVFLOW_LOCAL_MOTION_SOFTENING_V1" in MOTION_PATCHER
assert "smoothstep(0.03, 0.30," in MOTION_PATCHER
assert "neighborhood_quality(sampler2D field, vec2 uv, vec2 center)" in MOTION_PATCHER
assert "float luma_error = abs(dot(metric0" in MOTION_PATCHER
assert "float chroma_error = max(abs((metric0.r - metric0.g)" in MOTION_PATCHER
assert "boundary_trust *= mix(1.0, smoothstep(0.45, 0.70, local_quality)" in MOTION_PATCHER
assert "region3_local_trust = mix(1.0, smoothstep(0.30, 0.75, local_quality)" in MOTION_PATCHER
assert "min(0.75, 0.08 * local_motion_length)" in MOTION_PATCHER
assert "float local_motion_softness = 0.24 *" in MOTION_PATCHER
assert "sample_rgba(first, source_position + f.xy * scale + local_motion_offset)" in MOTION_PATCHER
assert "sample_rgba(second, source_position + r.xy * scale - local_motion_offset)" in MOTION_PATCHER
assert "PINNED_OUTPUT_PATTERN" in MOTION_PATCHER
assert "sample_rgba(source0, vec2(pixel) + f.xy * scale + local_motion_offset)" in MOTION_PATCHER
assert "sample_rgba(source1, vec2(pixel) + r.xy * scale - local_motion_offset)" in MOTION_PATCHER
assert "MPVFLOW_SOURCE_SPECIFIC_VISIBILITY_V1" in MOTION_PATCHER
assert "vec2 projection_ratio = vec2(phase_remaining / phase_alpha," in MOTION_PATCHER
assert "f.xy * scale * projection_ratio.x)" in MOTION_PATCHER
assert "r.xy * scale * projection_ratio.y)" in MOTION_PATCHER
assert "blend > 0.000001 && blend < 0.999999" in MOTION_PATCHER
assert "visibility += conflict_weight * phase * vec2(f.z, r.z) * evidence;" in MOTION_PATCHER
assert "return source_blend * (1.0 - visibility_sum) +" in MOTION_PATCHER
assert "MPVFLOW_RECONSTRUCTION_POSITIVE_EVIDENCE_V1" in MOTION_PATCHER
assert "evidence > 0.0 &&" in MOTION_PATCHER
assert "MPVFLOW_WARP_BEST_INVERSE_RESIDUAL_V1" in MOTION_PATCHER
assert "MPVFLOW_ARTIFACT_GUARDS_V4" in MOTION_SNAPSHOT
assert "MPVFLOW_WARP_TRAJECTORY_COHERENCE_V1" in MOTION_SNAPSHOT
assert "MPVFLOW_WARP_BEST_INVERSE_RESIDUAL_V1" in MOTION_SNAPSHOT
assert "candidate_residual < best_residual" in MOTION_SNAPSHOT
assert "MPVFLOW_WARP_TRAJECTORY_TRUST_V2" not in MOTION_SNAPSHOT
assert "MPVFLOW_INSHOT_NEAREST_SOURCE_FALLBACK_V1" not in MOTION_SNAPSHOT
assert "MPVFLOW_SCENE_CUT_SPATIAL_V1" in MOTION_SNAPSHOT
assert "MPVFLOW_FINE_MOTION_GRID_V1" in MOTION_SNAPSHOT
assert "MPVFLOW_HIGH_RES_FINE_GRID_V1" in MOTION_SNAPSHOT
assert "MPVFLOW_REFINEMENT_APERTURE_NORMAL_V1" in MOTION_SNAPSHOT
assert "float tensor_discriminant = sqrt(max(" in MOTION_SNAPSHOT
assert "lambda_min < 0.12 * lambda_max;" in MOTION_SNAPSHOT
assert "normalize(vec2(lambda_max - yy, xy))" in MOTION_SNAPSHOT
assert "float projected = dot(vector, aperture_normal);" in MOTION_SNAPSHOT
assert "MPVFLOW_REFINEMENT_APERTURE_TANGENT_V1" in MOTION_SNAPSHOT
assert "vec2 candidate = clamp(vector + aperture_normal *" in MOTION_SNAPSHOT
assert "float(offset) - dot(vector, aperture_normal)" in MOTION_SNAPSHOT
assert "aperture_normal * float(offset)" not in MOTION_SNAPSHOT
assert "vec2 axis = xx > yy ? vec2(1, 0) : vec2(0, 1);" not in MOTION_SNAPSHOT
assert "MPVFLOW_SPATIAL_TRUST_DOWNWARD_CAP_V3" in MOTION_SNAPSHOT
assert "MPVFLOW_SPATIAL_MEDOID_FALLBACK_V1" in MOTION_SNAPSHOT
assert "spatial_trust_consensus(warp0, warp1, uv, f, r)" in MOTION_SNAPSHOT
assert "MPVFLOW_SPATIAL_VISIBILITY_CAP_V1" in MOTION_SNAPSHOT
assert "MPVFLOW_SOURCE_SPECIFIC_VISIBILITY_V1" in MOTION_SNAPSHOT
assert "vec2 projection_ratio = vec2(phase_remaining / phase_alpha," in MOTION_SNAPSHOT
assert "f.xy * scale * projection_ratio.x)" in MOTION_SNAPSHOT
assert "r.xy * scale * projection_ratio.y)" in MOTION_SNAPSHOT
assert "alpha > 0.000001 && alpha < 0.999999" in MOTION_SNAPSHOT
assert "visibility += conflict_weight * phase * vec2(f.z, r.z) * evidence;" in MOTION_SNAPSHOT
assert "visibility *= clamp(spatial_visibility_scale, 0.0, 1.0);" in MOTION_SNAPSHOT
assert "color = source_blend * (1.0 - visibility_sum) +" in MOTION_SNAPSHOT
assert "if (total < 0.25)\n            return -1.0;" in MOTION_SNAPSHOT
assert "float spatial_island = max(0.0, clamp(trust, 0.0, 1.0) - spatial_consensus);" in MOTION_SNAPSHOT
assert "trust = mix(trust, min(trust, spatial_consensus)," in MOTION_SNAPSHOT
assert "trust = mix(trust, spatial_consensus," not in MOTION_SNAPSHOT
assert "if (spatial_consensus >= 0.0) {" in MOTION_SNAPSHOT
assert "int spacing = (level == LEVELS - 1 || level == 0) ? 4 : 8;" in MOTION_SNAPSHOT
assert "if (level == 0 && scale < 0.333334)" in MOTION_SNAPSHOT
assert "spacing = 3;" in MOTION_SNAPSHOT
assert "MPVFLOW_LOCAL_MOTION_SOFTENING_V1" in MOTION_SNAPSHOT
assert "local_motion_softness" in MOTION_SNAPSHOT
assert "float trajectory_error = abs(weight) * length(motion_vector - initial_motion);" in MOTION_SNAPSHOT
assert "reliable *= trajectory_quality;" in MOTION_SNAPSHOT
assert "MPVFLOW_WARP_PRIMARY_TRUST_CAP_V1" in MOTION_SNAPSHOT
assert "trust = min(trust, trajectory_quality);" in MOTION_SNAPSHOT
assert "trust *= trajectory_quality;" not in MOTION_SNAPSHOT
assert "nearest_frame_weight" not in MOTION_SNAPSHOT
assert "source_blend = mix(source_blend, nearest_frame," not in MOTION_SNAPSHOT
assert "source = uv - weight * f.xy / map_size;" in MOTION_SNAPSHOT
assert "float residual = best_residual;" in MOTION_SNAPSHOT
assert "test == 6" in FONGMI_GPU_TEST
assert "3.0f * (x - 63.9f) + 0.2f" in FONGMI_GPU_TEST
assert "fabs(output[index] + 0.25f)" in FONGMI_GPU_TEST
assert "output[index + 2] > 0.99" in FONGMI_GPU_TEST
assert "MPVFLOW_RECONSTRUCTION_POSITIVE_EVIDENCE_V1" in MOTION_SNAPSHOT
assert "if (!(evidence > 0.0 &&" in MOTION_SNAPSHOT
assert "if (!(evidence > best || (evidence == best && distance < nearest)))" not in MOTION_SNAPSHOT
assert "-Dgpl=false" in BUILD and "-Ddvdnav=disabled" in BUILD
assert "-Ddefault_library=static" in BUILD
assert 'PlaybackSession.setOptionString("motion-interpolation", targetFps.toString())' in MPV_VIEW
assert not re.search(r'PlaybackSession\.setOptionString\(\s*"interpolation"', MPV_VIEW), "FongMi must not enable mpv's separate interpolation mixer"

patch_order = list(PATCH_HASHES)
for patch_name, expected_hash in PATCH_HASHES.items():
    patch_path = PATCH_DIR / patch_name
    assert patch_path.is_file(), f"missing MPV-RX FongMi patch: {patch_path}"
    assert hashlib.sha256(patch_path.read_bytes()).hexdigest() == expected_hash, patch_name
    assert expected_hash in PATCHER, f"patcher does not pin {patch_name}"
assert [PATCHER.index(name) for name in patch_order] == sorted(PATCHER.index(name) for name in patch_order)
assert "patch --batch --forward --fuzz=0 --dry-run" in PATCHER
assert "patch --batch --reverse --fuzz=0 --dry-run" in PATCHER
assert "bd_seek_time_checked" in PATCHER and "bd_get_titles_with_menu_info" in PATCHER
patch_call = '"$ROOT/scripts/patches/mpvlibandroid-fongmi/patch-libbluray.sh" "$LIBBLURAY_SOURCE"'
assert patch_call in BUILD
assert BUILD.index('tar -xJf "$LIBBLURAY_ARCHIVE"') < BUILD.index(patch_call) < BUILD.index('./include/ci.sh install')
assert MPVLIB_ANDROID_SHA in PATCH_README

for workflow in WORKFLOWS:
    assert MPV_SHA in workflow
    assert PLACEBO_SHA in workflow
    assert "scripts/patches/mpvlibandroid-fongmi/**" in workflow
    assert "repository: FongMi/mpv" in workflow
    assert "runtime-v6-builder" in workflow
    assert f"ffmpeg-fongmi-{FFMPEG_SHA}" in workflow
    assert f"vulkan-headers-khronos-{VULKAN_HEADERS_SHA}" in workflow
    assert "scripts/prepare-mpvflow-vulkan-headers.py" in workflow
    assert "runtime-legacy" not in workflow
    assert "c1529642089bfebfc928a1c1664638a7a5d219ba" not in workflow
    assert "scripts/patch_fongmi_motion_artifact.py" in workflow

for text in (MANIFEST, PACKAGED):
    assert MPV_SHA in text
    assert PLACEBO_SHA in text
    assert MPVLIB_ANDROID_SHA in text
    assert FFMPEG_SHA in text
    assert f"mpvflow-fongmi-{FFMPEG_SHA}" in text
    assert VULKAN_HEADERS_SHA in text
    for patch_name, expected_hash in PATCH_HASHES.items():
        assert patch_name in text and expected_hash in text
    assert "https://github.com/FongMi/mpv" in text
    assert "https://github.com/FongMi/libplacebo" in text
    assert "https://github.com/FongMi/FFmpeg" in text
    assert "https://github.com/KhronosGroup/Vulkan-Headers" in text
    assert "patch_fongmi_motion_artifact.py" in text
    assert "vulkan-sdk-1.4.328.0" in text
    assert "Apache-2.0" in text and "MIT" in text
    assert "GPL-2.0" in text
    assert "does not apply the obsolete local patch or duplicate source files" in text

assert MPV_SHA in LICENSES
assert PLACEBO_SHA in LICENSES
assert MPVLIB_ANDROID_SHA in LICENSES
assert FFMPEG_SHA in LICENSES
assert "FongMi/FFmpeg" in LICENSES and "release-9.1-fongmi" in LICENSES
assert FFMPEG_SHA in ABOUT
assert f"https://github.com/FongMi/FFmpeg/commit/{FFMPEG_SHA}" in ABOUT
assert "LICENSE.GPL" in LICENSES
assert "patch_fongmi_motion_artifact.py" in LICENSES
assert GPL_LICENSE.is_file()
assert hashlib.sha256(GPL_LICENSE.read_bytes()).hexdigest() == GPL_SHA
gpl_text = GPL_LICENSE.read_text(encoding="utf-8")
assert "GNU GENERAL PUBLIC LICENSE" in gpl_text and "Version 2, June 1991" in gpl_text
assert "static void oblique_texture_translation(" in FONGMI_GPU_TEST
assert "oblique texture alpha=%.2f motion_error=%.6f blend_error=%.6f" in FONGMI_GPU_TEST
assert "oblique_texture_translation(vulkan->gpu, motion);" in FONGMI_GPU_TEST
print("native FongMi MPV, libplacebo, FFmpeg and Vulkan-Headers pins, libbluray patch set, provenance, and cache guards passed")
