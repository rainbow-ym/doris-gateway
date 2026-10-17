package com.example.doris.gateway.mask;

import org.apache.shardingsphere.mask.spi.MaskAlgorithm;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Masks a string according to its Unicode code-point length.
 *
 * <p>The rules property uses the format {@code length=keepFirst,keepLast} with
 * semicolon-separated entries. The {@code *} entry is mandatory and is used
 * as the default. Example: {@code 1=0,0;2=1,0;3=1,1;*=1,1}.</p>
 */
public final class LengthBasedMaskAlgorithm implements MaskAlgorithm<Object, String> {

    private static final String RULES = "rules";

    private static final String REPLACE_CHAR = "replace-char";

    private Map<Integer, KeepRule> exactRules = Collections.emptyMap();

    private KeepRule defaultRule;

    private int replacementCodePoint;

    @Override
    public void init(final Properties props) {
        replacementCodePoint = parseSingleCodePoint(props.getProperty(REPLACE_CHAR, "*"), REPLACE_CHAR);
        parseRules(props.getProperty(RULES));
    }

    private void parseRules(final String configuredRules) {
        if (null == configuredRules || configuredRules.trim().isEmpty()) {
            throw new IllegalArgumentException("Property 'rules' must not be empty.");
        }
        Map<Integer, KeepRule> parsedRules = new HashMap<>();
        KeepRule parsedDefault = null;
        for (String each : configuredRules.split(";")) {
            String[] assignment = each.trim().split("=", -1);
            if (2 != assignment.length) {
                throw new IllegalArgumentException("Invalid length mask rule: " + each);
            }
            KeepRule rule = parseKeepRule(assignment[1], each);
            String selector = assignment[0].trim();
            if ("*".equals(selector)) {
                parsedDefault = rule;
            } else {
                int length = parseNonNegativeInteger(selector, "length selector");
                if (null != parsedRules.put(length, rule)) {
                    throw new IllegalArgumentException("Duplicate length mask rule for length " + length + '.');
                }
            }
        }
        if (null == parsedDefault) {
            throw new IllegalArgumentException("Property 'rules' must contain a '*' default rule.");
        }
        exactRules = Collections.unmodifiableMap(parsedRules);
        defaultRule = parsedDefault;
    }

    private KeepRule parseKeepRule(final String value, final String sourceRule) {
        String[] pair = value.trim().split(",", -1);
        if (2 != pair.length) {
            throw new IllegalArgumentException("Invalid keep-first/keep-last pair in rule: " + sourceRule);
        }
        return new KeepRule(parseNonNegativeInteger(pair[0].trim(), "keep-first"), parseNonNegativeInteger(pair[1].trim(), "keep-last"));
    }

    private int parseNonNegativeInteger(final String value, final String name) {
        try {
            int result = Integer.parseInt(value);
            if (result < 0) {
                throw new IllegalArgumentException(name + " must not be negative.");
            }
            return result;
        } catch (final NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be an integer: " + value, ex);
        }
    }

    private int parseSingleCodePoint(final String value, final String name) {
        if (null == value || value.codePointCount(0, value.length()) != 1) {
            throw new IllegalArgumentException("Property '" + name + "' must contain exactly one Unicode character.");
        }
        return value.codePointAt(0);
    }

    @Override
    public String mask(final Object plainValue) {
        if (null == plainValue) {
            return null;
        }
        String value = String.valueOf(plainValue);
        int length = value.codePointCount(0, value.length());
        KeepRule rule = exactRules.getOrDefault(length, defaultRule);
        return CodePointMasking.keepFirstLast(value, rule.keepFirst, rule.keepLast, replacementCodePoint);
    }

    @Override
    public String getType() {
        return "LENGTH_BASED_MASK";
    }

    private static final class KeepRule {

        private final int keepFirst;

        private final int keepLast;

        private KeepRule(final int keepFirst, final int keepLast) {
            this.keepFirst = keepFirst;
            this.keepLast = keepLast;
        }
    }
}
