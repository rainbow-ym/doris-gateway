package com.example.doris.gateway.security;
import java.util.*;
/** Parser-only view: preserve offsets and original execution SQL. */
public final class DorisTableProperties {
    private DorisTableProperties() { }
    public static String parserView(String sql) {
        List<String> words=new ArrayList<>();
        int depth=0, propertyStart=-1, propertyDepth=-1;
        char[] view=sql.toCharArray();
        for(int i=0;i<sql.length();) {
            char c=sql.charAt(i);
            if(Character.isWhitespace(c)||c=='\uFEFF') {i++;continue;}
            if(c=='/'&&i+1<sql.length()&&sql.charAt(i+1)=='*') {
                int end=sql.indexOf("*/",i+2);if(end<0)return sql;i=end+2;continue;
            }
            if(c=='#'||(c=='-'&&i+1<sql.length()&&sql.charAt(i+1)=='-')) {
                while(i<sql.length()&&sql.charAt(i)!='\n')i++;continue;
            }
            if(c=='\''||c=='"'||c=='`') {
                char quote=c;int start=i++;
                while(i<sql.length()) {char q=sql.charAt(i++);if(q=='\\'&&i<sql.length()){i++;continue;}if(q==quote){if(i<sql.length()&&sql.charAt(i)==quote){i++;continue;}break;}}
                if(depth==0)words.add(sql.substring(start,i));continue;
            }
            if(c=='(') {depth++;if(propertyStart>=0&&propertyDepth<0)propertyDepth=depth;i++;continue;}
            if(c==')') {
                if(propertyStart>=0&&depth==propertyDepth) {
                    for(int j=propertyStart;j<=i;j++)if(!Character.isWhitespace(view[j]))view[j]=' ';
                    return new String(view);
                }
                depth--;i++;continue;
            }
            if(Character.isLetterOrDigit(c)||c=='_') {
                int start=i++;while(i<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i))||sql.charAt(i)=='_'))i++;
                if(depth==0) {
                    String word=sql.substring(start,i).toUpperCase(Locale.ROOT);
                    if(words.size()>=2&&"CREATE".equals(words.get(0))&&"TABLE".equals(words.get(1))) {
                        if("AS".equals(word))return sql;
                        if(words.size()>=3&&"PROPERTIES".equals(word)) {
                            int next=i;while(next<sql.length()&&Character.isWhitespace(sql.charAt(next)))next++;
                            if(next<sql.length()&&sql.charAt(next)=='(')propertyStart=start;
                        }
                    }
                    words.add(word);
                }
                continue;
            }
            i++;
        }
        return sql;
    }
}
