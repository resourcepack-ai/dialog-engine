package ai.resourcepack.dialogs.core.drawn;

import ai.resourcepack.dialogs.core.platform.Platform;

/**
 * Whether this server's dialogs may carry the {@code player} text component
 * (1.21.9+). The build puts this in for RP Engine's own version check when it
 * copies RP Engine's dialog code into this package.
 */
final class Heads {

    private Heads() {
    }

    static boolean supported() {
        // Read leniently: Paper 26.x says "26.3.build.12-stable", and reading
        // "build" as a number threw, which the caller takes as "no heads" — so
        // no face was ever drawn on Paper 26.x before 0.4.2.
        int[] v = Platform.minecraftVersionNumbers();
        if (v[0] != 1) return v[0] > 1;
        return v[1] > 21 || (v[1] == 21 && v[2] >= 9);
    }
}
