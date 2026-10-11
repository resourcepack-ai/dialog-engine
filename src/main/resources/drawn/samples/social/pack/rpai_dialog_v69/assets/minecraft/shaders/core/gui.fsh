#version 330

// Can't moj_import in things used during startup, when resource packs don't exist.
// This is a copy of dynamicimports.glsl
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
    float LineWidth;
};

in vec4 vertexColor;

out vec4 fragColor;

// ResourcePack AI: hides the white frame the game draws round a dialog's body
// when it is clicked. Vanilla's own shader is further down, unchanged but for
// its name.
const int RPAI_BODY_A = 400;
const int RPAI_BODY_B = 256;
const int RPAI_INSET = 4;
const int RPAI_SLACK = 3;
noperspective in vec4 rpaiCorner0;
noperspective in vec4 rpaiCorner1;
noperspective in vec4 rpaiCorner2;
noperspective in vec4 rpaiCorner3;
noperspective in vec4 rpaiPresent;
noperspective in float rpaiCandidate;

bool rpaiIsOutline(vec4 c0, vec4 c1, vec4 c2, vec4 c3, vec4 present, float candidate) {
    // Every corner of this triangle has to have been one of an outline's.
    if (candidate < 0.999) return false;
    vec4 corners[4] = vec4[4](c0, c1, c2, c3);
    int count = 0;
    int edgeLo = 99;
    int edgeHi = 0;
    int offsetLo = 99;
    int offsetHi = -99;
    int ninthA = -1;
    int ninthB = -1;
    int sixteenthA = -1;
    int sixteenthB = -1;
    for (int k = 0; k < 4; k++) {
        float weight = present[k];
        // Not one of this triangle's corners, or this fragment is on the far
        // side of the triangle from it.
        if (weight < 0.00002) continue;
        vec4 c = corners[k] / weight;
        int edge = int(floor(c.w + 0.5));
        if (edge < 1) return false;
        int offset = int(floor(c.x + 0.5));
        int ninth = int(floor(c.y + 0.5));
        int sixteenth = int(floor(c.z + 0.5));
        count++;
        edgeLo = min(edgeLo, edge);
        edgeHi = max(edgeHi, edge);
        offsetLo = min(offsetLo, offset);
        offsetHi = max(offsetHi, offset);
        if (ninthA < 0 || ninthA == ninth) ninthA = ninth;
        else if (ninthB < 0 || ninthB == ninth) ninthB = ninth;
        else return false;
        if (sixteenthA < 0 || sixteenthA == sixteenth) sixteenthA = sixteenth;
        else if (sixteenthB < 0 || sixteenthB == sixteenth) sixteenthB = sixteenth;
        else return false;
    }
    if (count < 2 || ninthB < 0) return false;
    int ninths = (ninthB - ninthA + 9) % 9;
    if (edgeLo == edgeHi) {
        // A side: one pixel wide, its ends nine pixels a line plus six apart.
        return offsetHi - offsetLo == 1 && (ninths == 3 || ninths == 6);
    }
    // The top or the bottom: from one body's left edge to its own right edge,
    // exactly as wide as that body, one pixel tall.
    if (sixteenthB < 0) return false;
    int sixteenths = (sixteenthB - sixteenthA + 16) % 16;
    return edgeHi == edgeLo + 1 && edgeLo % 2 == 1 && offsetLo == offsetHi
        && (ninths == 1 || ninths == 8) && (sixteenths == 1 || sixteenths == 15);
}

void rpai_gui_main() {
    vec4 color = vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color * ColorModulator;
}

void main() {
    if (rpaiIsOutline(rpaiCorner0, rpaiCorner1, rpaiCorner2, rpaiCorner3, rpaiPresent, rpaiCandidate)) {
        discard;
    }
    rpai_gui_main();
}
