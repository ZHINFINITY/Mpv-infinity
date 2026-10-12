#version 450

layout(set = 0, binding = 0) uniform sampler2D source_image;
layout(location = 0) out vec4 target_color;

layout(push_constant) uniform PushConstants {
    ivec2 crop_offset;
    ivec2 crop_size;
    ivec2 output_size;
    ivec2 source_size;
} params;

void main()
{
    ivec2 position = ivec2(gl_FragCoord.xy);
    vec2 source_position =
        vec2(params.crop_offset) +
        (vec2(position) + vec2(0.5)) * vec2(params.crop_size) /
            vec2(params.output_size);
    // The imported AHardwareBuffer extent is authoritative for external-format
    // images and avoids relying on driver image-size queries.
    vec2 uv = source_position / vec2(params.source_size);
    target_color = texture(source_image, uv);
}
