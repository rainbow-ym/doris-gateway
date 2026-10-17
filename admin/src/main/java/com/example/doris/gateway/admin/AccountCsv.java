package com.example.doris.gateway.admin;

import java.util.*;

/** Small bounded RFC4180-style reader, including quoted commas/newlines and escaped quotes. */
public final class AccountCsv {
    private AccountCsv(){}
    public static List<Map<String,String>> read(String text) {
        if(text==null||text.length()>1024*1024)throw new IllegalArgumentException("账号文件为空或超过 1 MB。");
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        List<List<String>> records=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false,closed=false;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(quoted){if(c=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}else cell.append(c);continue;}
            if(c=='"'){if(cell.length()!=0||closed)throw new IllegalArgumentException("CSV 引号格式错误。");quoted=true;continue;}
            if(c==','||c=='\r'||c=='\n'){
                row.add(cell.toString());cell.setLength(0);closed=false;
                if(c!=','){if(row.size()>1||!row.get(0).isEmpty())records.add(row);row=new ArrayList<>();if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;}
            }else{if(closed)throw new IllegalArgumentException("CSV 引号后存在多余字符。");cell.append(c);}
        }
        if(quoted)throw new IllegalArgumentException("CSV 引号未闭合。");
        if(cell.length()>0||closed||!row.isEmpty()){row.add(cell.toString());records.add(row);}
        if(records.size()<2||records.size()>201)throw new IllegalArgumentException("每次请导入 1—200 个账号。");
        List<String> header=records.remove(0);Set<String> columns=new HashSet<>(header);
        if(!columns.containsAll(Arrays.asList("username","password"))||columns.size()!=header.size()||!Arrays.asList("username","password","databasePattern").containsAll(columns))
            throw new IllegalArgumentException("表头必须包含 username,password，可选 databasePattern，不允许重复或未知列。");
        Map<String,Integer> seen=new HashMap<>();Map<String,Integer> folded=new HashMap<>();List<Map<String,String>> result=new ArrayList<>();
        for(List<String> values:records){
            if(values.size()!=header.size())throw new IllegalArgumentException("CSV 第 "+(result.size()+2)+" 行列数不匹配。");
            Map<String,String> item=new LinkedHashMap<>();for(int i=0;i<header.size();i++)item.put(header.get(i),values.get(i));
            int line=result.size()+2;String user=item.get("username");
            try{com.example.doris.gateway.account.AccountName.validate(user);}catch(IllegalArgumentException e){throw new IllegalArgumentException("CSV 第 "+line+" 条记录："+e.getMessage());}
            if(seen.containsKey(user))throw new IllegalArgumentException("CSV 第 "+line+" 条记录与第 "+seen.get(user)+" 条记录的用户名重复，请保留一条。");
            String lower=user.toLowerCase(Locale.ROOT);
            if(folded.containsKey(lower))throw new IllegalArgumentException("CSV 第 "+line+" 条记录与第 "+folded.get(lower)+" 条记录的用户名仅大小写不同，当前网关存在身份冲突；不是中文格式错误，也不会自动合并。");
            seen.put(user,line);folded.put(lower,line);
            if(item.get("password").isEmpty())throw new IllegalArgumentException("CSV 第 "+line+" 条记录未提供密码。");
            item.put("username",user);if(!item.containsKey("databasePattern")||item.get("databasePattern").isEmpty())item.put("databasePattern","%");result.add(item);
        }
        return result;
    }
}
