package com.example.doris.gateway.admin;

public final class Errors {
    private Errors() { }
    public static String explain(Throwable error) {
        String raw=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
        String lower=raw.toLowerCase(java.util.Locale.ROOT);
        if(lower.contains("access denied")) return "账号权限校验失败。请检查数据源账号的 Doris 库权限，以及平台独立控制账号的 Proxy 管理权限。修复后点击重试。";
        if(lower.contains("communications link") || lower.contains("connect timed out") || lower.contains("connection refused")) return "连接超时或服务不可达。请检查地址、端口与网络，再重试。";
        if(lower.contains("unknown database")) return "数据库不存在或当前账号不可见。请重新发现库表并检查授权。";
        return raw.replaceAll("(?i)(password\\s*[=:]\\s*)('[^']*'|[^,\\s&]+)","$1***");
    }
}
