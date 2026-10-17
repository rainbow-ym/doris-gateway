package com.example.doris.gateway.mask;

import java.util.Arrays;

/**
 * Unicode code-point based masking helpers.
 */
final class CodePointMasking {

    private CodePointMasking() {
    }

    static String keepFirstLast(final Object plainValue, final int keepFirst, final int keepLast, final int replacementCodePoint) {
        if (null == plainValue) {
            return null;
        }
        String value = String.valueOf(plainValue);
        if (value.isEmpty()) {
            return value;
        }
        int[] codePoints = value.codePoints().toArray();
        if ((long) keepFirst + keepLast >= codePoints.length) {
            // A short value must not become plaintext merely because the retained
            // portions overlap. Explicit CLEAR belongs to the user policy layer.
            Arrays.fill(codePoints, replacementCodePoint);
            return new String(codePoints, 0, codePoints.length);
        }
        int[] result = Arrays.copyOf(codePoints, codePoints.length);
        Arrays.fill(result, keepFirst, codePoints.length - keepLast, replacementCodePoint);
        return new String(result, 0, result.length);
    }
}
