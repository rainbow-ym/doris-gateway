package com.example.doris.gateway.admin;
import java.util.*;

/** Strict bounded RFC4180-style CSV. Header required; delimiter is not guessed. */
public final class ExportCsv {
    public static List<List<String>> parse(String text){
        if(text==null||text.length()>1024*1024)throw new IllegalArgumentException("CSV 为空或超过 1 MiB。");
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder value=new StringBuilder();
        boolean quoted=false,closed=false,started=false;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(quoted){if(c=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){value.append('"');i++;}else{quoted=false;closed=true;}}else value.append(c);continue;}
            if(c=='"'){if(started||closed)throw new IllegalArgumentException("CSV 引号位置不正确。");quoted=true;started=true;}
            else if(c==','||c=='\r'||c=='\n'){
                row.add(value.toString());value.setLength(0);started=false;closed=false;
                if(row.size()>256)throw new IllegalArgumentException("CSV 列数超限。");
                if(c!=','){rows.add(row);row=new ArrayList<>();if(rows.size()>1002)throw new IllegalArgumentException("CSV 行数超限。");if(c=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;}
            }else{if(closed)throw new IllegalArgumentException("CSV 闭合引号后存在额外字符。");value.append(c);started=true;}
        }
        if(quoted)throw new IllegalArgumentException("CSV 引号未闭合。");
        if(started||closed||!row.isEmpty()){row.add(value.toString());rows.add(row);}
        return rows;
    }
    public static String write(List<List<String>> rows){
        StringBuilder out=new StringBuilder("\uFEFF");
        for(List<String> row:rows){for(int i=0;i<row.size();i++){if(i>0)out.append(',');String v=row.get(i);if(v==null)v="";
            // Treat downloads as spreadsheet data, not executable formulas. This is disclosed in the UI.
            if(!v.isEmpty()&&"=+-@\t\r".indexOf(v.charAt(0))>=0)v="'"+v;
            out.append('"').append(v.replace("\"","\"\"")).append('"');}out.append("\r\n");}
        return out.toString();
    }
}
