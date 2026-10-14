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
layout(location = 1) in vec4 Color;

layout(location = 0) out vec4 vertexColor;

// ResourcePack AI: hides the white frame the game draws round a dialog's body
// when it is clicked. Vanilla's own shader is further down, unchanged but for
// its name.
const int RPAI_BODY_A = 400;
const int RPAI_BODY_B = 256;
const int RPAI_INSET = 0;
const int RPAI_SLACK = 3;
layout(location = 1) noperspective out vec4 rpaiCorner0;
layout(location = 2) noperspective out vec4 rpaiCorner1;
layout(location = 3) noperspective out vec4 rpaiCorner2;
layout(location = 4) noperspective out vec4 rpaiCorner3;
layout(location = 5) noperspective out vec4 rpaiPresent;
layout(location = 6) noperspective out float rpaiCandidate;

// The x of a dialog body's left edge on a screen this wide: centred in a
// scroll area 10px wider each side, pinned left when that does not fit.
int rpaiBodyLeft(int screenWidth, int width) {
    int content = width - 2 * RPAI_INSET;
    return max(0, screenWidth - content - 20) / 2 + 10 - RPAI_INSET;
}

vec4 rpaiOutlineCorner(vec2 position, vec4 color, float projectionX) {
    if (any(lessThan(color, vec4(1.0))) || projectionX <= 0.0) return vec4(0.0);
    vec2 whole = floor(position + 0.5);
    if (any(greaterThan(abs(position - whole), vec2(0.01)))) return vec4(0.0);
    int x = int(whole.x);
    // Lifted by a multiple of both moduli: a scrolled body's top is above the
    // screen, and % of a negative int is undefined.
    int y = int(whole.y) + 9216;
    if (y < 0) return vec4(0.0);
    int screenWidth = int(ceil(2.0 / projectionX - 0.01));
    for (int i = 0; i < 2; i++) {
        int width = i == 0 ? RPAI_BODY_A : RPAI_BODY_B;
        int left = rpaiBodyLeft(screenWidth, width);
        if (abs(x - left) <= RPAI_SLACK) {
            return vec4(float(x - left), float(y % 9), float(y % 16), float(2 * i + 1));
        }
        if (abs(x - left - width) <= RPAI_SLACK) {
            return vec4(float(x - left - width), float(y % 9), float(y % 16), float(2 * i + 2));
        }
    }
    return vec4(0.0);
}

void rpai_gui_main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexColor = Color;
}

void main() {
    rpai_gui_main();
    vec4 corner = rpaiOutlineCorner(Position.xy, Color, ProjMat[0][0]);
    // Any four vertices in a row are four different slots, which is all the
    // fragment stage needs to tell a triangle's corners apart.
    rpaiPresent = vec4(equal(ivec4(gl_VertexIndex % 4), ivec4(0, 1, 2, 3)));
    rpaiCandidate = corner.w > 0.0 ? 1.0 : 0.0;
    rpaiCorner0 = corner * rpaiPresent.x;
    rpaiCorner1 = corner * rpaiPresent.y;
    rpaiCorner2 = corner * rpaiPresent.z;
    rpaiCorner3 = corner * rpaiPresent.w;
}
