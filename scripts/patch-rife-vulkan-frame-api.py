#!/usr/bin/env python3
"""Patch the pinned RIFE source with an explicit GPU-resident frame API.

This adds a Vulkan tensor-in/tensor-out inference method. It deliberately does
not claim to connect mpv's MediaCodec AHardwareBuffer or renderer yet.
"""
from __future__ import annotations

import argparse
from pathlib import Path

HEADER_MARKER = "// RIFE_VFI_VULKAN_FRAME_API"
SOURCE_MARKER = "// RIFE_VFI_VULKAN_FRAME_API_IMPLEMENTATION"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one {label} anchor, found {count}")
    return text.replace(old, new, 1)


def patch_header(text: str) -> str:
    if HEADER_MARKER in text:
        return text

    text = replace_once(
        text,
        "    int process_v4(const ncnn::Mat& in0image, const ncnn::Mat& in1image, float timestep, ncnn::Mat& outimage) const;",
        "    int process_v4(const ncnn::Mat& in0image, const ncnn::Mat& in1image, float timestep, ncnn::Mat& outimage) const;\n"
        "    // Vulkan-resident RGB tensors in/out; caller owns input and output VkMat lifetimes.\n"
        "    int process_v4_gpu(const ncnn::VkMat& in0image, const ncnn::VkMat& in1image, float timestep, ncnn::VkMat& outimage) const;\n"
        f"    {HEADER_MARKER}",
        "RIFE public process_v4 declaration",
    )
    text = replace_once(
        text,
        "private:\n",
        "private:\n"
        "    int process_v4_internal(const ncnn::Mat& in0image, const ncnn::Mat& in1image, float timestep, ncnn::Mat& outimage,\n"
        "                           const ncnn::VkMat* gpu_in0, const ncnn::VkMat* gpu_in1, ncnn::VkMat* gpu_out) const;\n",
        "RIFE private section",
    )
    return text


def patch_source(text: str) -> str:
    if SOURCE_MARKER in text:
        return text

    signature = (
        "int RIFE::process_v4(const ncnn::Mat& in0image, const ncnn::Mat& in1image, "
        "float timestep, ncnn::Mat& outimage) const\n{"
    )
    wrappers = (
        "int RIFE::process_v4(const ncnn::Mat& in0image, const ncnn::Mat& in1image, "
        "float timestep, ncnn::Mat& outimage) const\n{\n"
        "    return process_v4_internal(in0image, in1image, timestep, outimage, 0, 0, 0);\n"
        "}\n\n"
        "int RIFE::process_v4_gpu(const ncnn::VkMat& in0image, const ncnn::VkMat& in1image, "
        "float timestep, ncnn::VkMat& outimage) const\n{\n"
        "    ncnn::Mat unused_input0;\n"
        "    ncnn::Mat unused_input1;\n"
        "    ncnn::Mat unused_output;\n"
        "    return process_v4_internal(unused_input0, unused_input1, timestep, unused_output, &in0image, &in1image, &outimage);\n"
        "}\n\n"
        "int RIFE::process_v4_internal(const ncnn::Mat& in0image, const ncnn::Mat& in1image, "
        "float timestep, ncnn::Mat& outimage, const ncnn::VkMat* gpu_in0, "
        "const ncnn::VkMat* gpu_in1, ncnn::VkMat* gpu_out) const\n{\n"
        f"    {SOURCE_MARKER}"
    )
    text = replace_once(text, signature, wrappers, "RIFE process_v4 implementation")

    body_start = text.index("int RIFE::process_v4_internal")
    body_end = text.index("\nint RIFE::process_v4_cpu", body_start)
    body = text[body_start:body_end]

    body = replace_once(
        body,
        "    if (!vkdev)\n    {\n        // cpu only\n        return process_cpu(in0image, in1image, timestep, outimage);\n    }",
        "    if ((gpu_in0 == 0) != (gpu_in1 == 0) ||\n"
        "        ((gpu_in0 != 0) != (gpu_out != 0)))\n"
        "        return -1;\n"
        "    if (!vkdev)\n    {\n"
        "        // A GPU frame must never silently fall back through host pixels.\n"
        "        if (gpu_in0 || gpu_in1 || gpu_out)\n"
        "            return -1;\n"
        "        // cpu only\n"
        "        return process_cpu(in0image, in1image, timestep, outimage);\n"
        "    }",
        "RIFE Vulkan/CPU dispatch guard",
    )

    body = replace_once(
        body,
        "    if (timestep == 0.f)\n    {\n        outimage = in0image;\n        return 0;\n    }\n\n    if (timestep == 1.f)\n    {\n        outimage = in1image;\n        return 0;\n    }",
        "    if (timestep == 0.f)\n    {\n"
        "        if (gpu_in0)\n            *gpu_out = *gpu_in0;\n"
        "        else\n            outimage = in0image;\n"
        "        return 0;\n    }\n\n"
        "    if (timestep == 1.f)\n    {\n"
        "        if (gpu_in1)\n            *gpu_out = *gpu_in1;\n"
        "        else\n            outimage = in1image;\n"
        "        return 0;\n    }",
        "RIFE endpoint-frame handling",
    )

    body = replace_once(
        body,
        "    const unsigned char* pixel0data = (const unsigned char*)in0image.data;\n"
        "    const unsigned char* pixel1data = (const unsigned char*)in1image.data;\n"
        "    const int w = in0image.w;\n"
        "    const int h = in0image.h;",
        "    const unsigned char* pixel0data = gpu_in0 ? 0 : (const unsigned char*)in0image.data;\n"
        "    const unsigned char* pixel1data = gpu_in1 ? 0 : (const unsigned char*)in1image.data;\n"
        "    const int w = gpu_in0 ? gpu_in0->w : in0image.w;\n"
        "    const int h = gpu_in0 ? gpu_in0->h : in0image.h;\n"
        "    if (w <= 0 || h <= 0 ||\n"
        "        (gpu_in0 && (gpu_in0->empty() || gpu_in1->empty() ||\n"
        "                     gpu_in0->w != gpu_in1->w || gpu_in0->h != gpu_in1->h ||\n"
        "                     gpu_in0->dims != 3 || gpu_in1->dims != 3 ||\n"
        "                     gpu_in0->c != 3 || gpu_in1->c != 3 ||\n"
        "                     gpu_in0->elempack != 1 || gpu_in1->elempack != 1 ||\n"
        "                     gpu_in0->elemsize != (flownet.opt.use_fp16_storage ? 2u : 4u) ||\n"
        "                     gpu_in1->elemsize != (flownet.opt.use_fp16_storage ? 2u : 4u))))\n"
        "        return -1;",
        "RIFE source dimensions and Vulkan tensor contract",
    )

    mat_block = (
        "    ncnn::Mat in0;\n"
        "    ncnn::Mat in1;\n"
        "    if (opt.use_fp16_storage && opt.use_int8_storage)\n"
        "    {\n"
        "        in0 = ncnn::Mat(w, h, (unsigned char*)pixel0data, (size_t)channels, 1);\n"
        "        in1 = ncnn::Mat(w, h, (unsigned char*)pixel1data, (size_t)channels, 1);\n"
        "    }\n"
        "    else\n"
        "    {\n"
        "#if _WIN32\n"
        "        in0 = ncnn::Mat::from_pixels(pixel0data, ncnn::Mat::PIXEL_BGR2RGB, w, h);\n"
        "        in1 = ncnn::Mat::from_pixels(pixel1data, ncnn::Mat::PIXEL_BGR2RGB, w, h);\n"
        "#else\n"
        "        in0 = ncnn::Mat::from_pixels(pixel0data, ncnn::Mat::PIXEL_RGB, w, h);\n"
        "        in1 = ncnn::Mat::from_pixels(pixel1data, ncnn::Mat::PIXEL_RGB, w, h);\n"
        "#endif\n"
        "    }"
    )
    converted_mat_block = (
        "    ncnn::Mat in0;\n"
        "    ncnn::Mat in1;\n"
        "    if (!gpu_in0)\n    {\n"
        + "\n".join("    " + line for line in mat_block.splitlines()[2:])
        + "\n    }"
    )
    body = replace_once(body, mat_block, converted_mat_block, "RIFE CPU pixel conversion block")

    body = replace_once(
        body,
        "    ncnn::VkMat in0_gpu;\n"
        "    ncnn::VkMat in1_gpu;\n"
        "    {\n"
        "        cmd.record_clone(in0, in0_gpu, opt);\n"
        "        cmd.record_clone(in1, in1_gpu, opt);\n"
        "    }",
        "    ncnn::VkMat in0_gpu;\n"
        "    ncnn::VkMat in1_gpu;\n"
        "    if (gpu_in0)\n    {\n"
        "        in0_gpu = *gpu_in0;\n"
        "        in1_gpu = *gpu_in1;\n"
        "    }\n    else\n    {\n"
        "        cmd.record_clone(in0, in0_gpu, opt);\n"
        "        cmd.record_clone(in1, in1_gpu, opt);\n"
        "    }",
        "RIFE Vulkan input upload block",
    )

    download_start = body.index("    // download\n")
    reclaim_anchor = "    vkdev->reclaim_blob_allocator(blob_vkallocator);"
    download_end = body.index(reclaim_anchor, download_start)
    download_block = body[download_start:download_end]
    indented_download = "\n".join(
        "    " + line if line else line
        for line in download_block.rstrip("\n").splitlines()
    )
    body = (
        body[:download_start]
        + "    if (gpu_out)\n    {\n"
        + "        cmd.submit_and_wait();\n"
        + "        *gpu_out = out_gpu;\n"
        + "    }\n    else\n    {\n"
        + indented_download
        + "\n    }\n"
        + body[download_end:]
    )

    return text[:body_start] + body + text[body_end:]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("rife_src", type=Path, help="Pinned RIFE src directory")
    args = parser.parse_args()
    header_path = args.rife_src / "rife.h"
    source_path = args.rife_src / "rife.cpp"
    if not header_path.is_file() or not source_path.is_file():
        raise SystemExit(f"Pinned RIFE source is incomplete: {args.rife_src}")

    header = patch_header(header_path.read_text())
    source = patch_source(source_path.read_text())
    header_path.write_text(header)
    source_path.write_text(source)
    print("Enabled RIFE Vulkan VkMat-in/VkMat-out API; legacy RGB24 path remains unchanged")


if __name__ == "__main__":
    main()
