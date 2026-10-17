package com.example.doris.gateway.account;

/** Shared compatibility boundary; Doris remains responsible for authenticating the account. */
public final class AccountName {
    private AccountName() { }
    public static String validate(String value) {
        if(value==null||value.isEmpty())throw new IllegalArgumentException("用户名不能为空。");
        if(value.length()>128)throw new IllegalArgumentException("用户名超过当前网关存储上限 128 个 UTF-16 单元；这是网关限制，不是 Doris 账号无效。");
        if(!value.equals(value.trim()))throw new IllegalArgumentException("用户名含首尾空白，请核实原始账号；网关不会自动修改用户名。");
        if(!value.matches("[\\p{L}\\p{N}_.-]+"))throw new IllegalArgumentException("当前网关用户名支持中文等 Unicode 字母、数字、下划线、点和短横线；不支持控制字符或账号映射分隔符。");
        return value;
    }
}
