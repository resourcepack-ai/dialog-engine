#version 330
#extension GL_ARB_separate_shader_objects : require

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif

layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

void rpe_head_vertex_main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;
}

// rpai-merge:begin vertex-vanilla v1
// #include <minecraft:dynamictransforms.glsl>
// #include <minecraft:projection.glsl>
// layout(location = 0) in vec3 Position;
// layout(location = 1) in vec4 Color;
// layout(location = 2) in vec2 UV0;
// layout(location = 2) out vec4 vertexColor;
// layout(location = 3) out vec2 texCoord0;
// rpai-merge:end vertex-vanilla
// rpai-merge:begin vertex-epilogue v1
// rpai-merge:renames main rpe_head_vertex_main
void main() {
    rpe_head_vertex_main();

    // A live player head in a dialog: the game's 8x8 face, scaled from its
    // top-left to the size and dropped to the place its colour says.
    if (abs(ProjMat[3][3] - 1.0) < 0.001) {
        ivec3 rpeHead = ivec3(floor(Color.rgb * 255.0 + 0.5));
        vec2 rpeEighths = UV0 * 8.0;
        if (rpeHead.r == 252 && rpeHead.g >= 191 && rpeHead.g <= 247 && rpeHead.b >= 246
                && all(lessThan(abs(rpeEighths - floor(rpeEighths + 0.5)), vec2(0.01)))) {
            vec2 rpeSkin = UV0 * 64.0;
            float rpeSize = float(255 - rpeHead.g);
            float rpeDrop = float(255 - rpeHead.b);
            vec2 rpeCorner = vec2(mod(rpeSkin.x, 32.0) > 12.0 ? 1.0 : 0.0, rpeSkin.y > 12.0 ? 1.0 : 0.0);
            gl_Position.xy += (ProjMat * vec4(rpeCorner * (rpeSize - 8.0) + vec2(0.0, rpeDrop), 0.0, 0.0)).xy;
            vertexColor = vec4(1.0, 1.0, 1.0, Color.a);
        }
    }
}
// rpai-merge:end vertex-epilogue
