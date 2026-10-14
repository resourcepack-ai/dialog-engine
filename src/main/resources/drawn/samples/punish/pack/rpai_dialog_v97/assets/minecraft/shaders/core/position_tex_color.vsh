#version 330
#extension GL_ARB_separate_shader_objects : require

// Can't moj_import in things used during startup, when resource packs don't exist.
// This is a copy of dynamicimports.glsl and projection.glsl
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;

layout(location = 2) noperspective out float rpaiFooter;

// ResourcePack AI: hides a dialog's footer button, which the dialog draws
// itself.
float rpaiFooterCorner(vec2 position, mat4 projection) {
    // Orthographic only: the GUI. A perspective matrix has -1 here.
    if (projection[0][0] <= 0.0 || abs(projection[2][3]) > 0.001) return 0.0;
    float halfWidth = 1.0 / projection[0][0];
    float height = 2.0 / abs(projection[1][1]);
    if (abs(position.x - halfWidth) > 2.5) return 0.0;
    if (position.y < height - 34.0 || position.y > height + 1.0) return 0.0;
    return 1.0;
}

void rpai_sprite_main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    texCoord0 = UV0;
    vertexColor = Color;
}

void main() {
    rpai_sprite_main();
    rpaiFooter = rpaiFooterCorner((ModelViewMat * vec4(Position, 1.0)).xy, ProjMat);
}
