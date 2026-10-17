package com.example.doris.gateway.admin;

/**
 * Separates client-added SQL envelope comments from the executable statement.
 * The original SQL is retained for execution and audit; this view is used only
 * for statement classification, managed-object checks and lineage analysis.
 */
final class SqlTextEnvelope {

    private SqlTextEnvelope() {
    }

    static String executableStatement(final String sql) {
        if (sql == null) throw new IllegalArgumentException("SQL is required.");
        int offset = 0;
        if (!sql.isEmpty() && '\uFEFF' == sql.charAt(0)) offset++;
        while (true) {
            offset = skipWhitespace(sql, offset);
            if (startsWith(sql, offset, "/*")) {
                if (startsWith(sql, offset, "/*!")) {
                    throw new IllegalArgumentException("MySQL executable comments are not supported by managed SQL classification.");
                }
                int end = sql.indexOf("*/", offset + 2);
                if (end < 0) throw new IllegalArgumentException("Unterminated leading SQL block comment.");
                offset = end + 2;
                continue;
            }
            if (startsWith(sql, offset, "--") || startsWith(sql, offset, "#")) {
                offset = skipLine(sql, offset);
                continue;
            }
            break;
        }
        String result = sql.substring(offset).trim();
        if (result.isEmpty()) throw new IllegalArgumentException("SQL contains no executable statement.");
        return result;
    }

    private static int skipWhitespace(final String sql, final int offset) {
        int result = offset;
        while (result < sql.length() && Character.isWhitespace(sql.charAt(result))) result++;
        return result;
    }

    private static int skipLine(final String sql, final int offset) {
        int result = offset;
        while (result < sql.length() && '\n' != sql.charAt(result) && '\r' != sql.charAt(result)) result++;
        while (result < sql.length() && ('\n' == sql.charAt(result) || '\r' == sql.charAt(result))) result++;
        return result;
    }

    private static boolean startsWith(final String value, final int offset, final String prefix) {
        return offset >= 0 && offset + prefix.length() <= value.length() && value.startsWith(prefix, offset);
    }
}
