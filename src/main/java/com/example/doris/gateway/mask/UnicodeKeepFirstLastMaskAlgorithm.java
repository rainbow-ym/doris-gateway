package com.example.doris.gateway.mask;

import org.apache.shardingsphere.mask.spi.MaskAlgorithm;

import java.util.Properties;

/**
 * Keeps the first N and last M Unicode code points and masks the middle.
 */
public final class UnicodeKeepFirstLastMaskAlgorithm implements MaskAlgorithm<Object, String> {

    private static final String FIRST_N = "first-n";

    private static final String LAST_M = "last-m";

    private static final String REPLACE_CHAR = "replace-char";

    private int firstN;

    private int lastM;

    private int replacementCodePoint;

    @Override
    public void init(final Properties props) {
        firstN = parseNonNegativeInteger(props.getProperty(FIRST_N), FIRST_N);
        lastM = parseNonNegativeInteger(props.getProperty(LAST_M), LAST_M);
        String replacement = props.getProperty(REPLACE_CHAR, "*");
        if (replacement.codePointCount(0, replacement.length()) != 1) {
            throw new IllegalArgumentException("Property 'replace-char' must contain exactly one Unicode character.");
        }
        replacementCodePoint = replacement.codePointAt(0);
    }

    private int parseNonNegativeInteger(final String value, final String name) {
        if (null == value) {
            throw new IllegalArgumentException("Property '" + name + "' must not be null.");
        }
        try {
            int result = Integer.parseInt(value);
            if (result < 0) {
                throw new IllegalArgumentException("Property '" + name + "' must not be negative.");
            }
            return result;
        } catch (final NumberFormatException ex) {
            throw new IllegalArgumentException("Property '" + name + "' must be an integer.", ex);
        }
    }

    @Override
    public String mask(final Object plainValue) {
        return CodePointMasking.keepFirstLast(plainValue, firstN, lastM, replacementCodePoint);
    }

    @Override
    public String getType() {
        return "UNICODE_KEEP_FIRST_N_LAST_M";
    }
}
