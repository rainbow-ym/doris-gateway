package com.example.doris.gateway.account;
import java.util.*;
/** Bounded comment-aware grammar. Shared verbatim by proxy and worker. Never log SQL or password. */
public final class AccountCommand {
 public String operation,username,password; public boolean conditional;
 private final List<Token> tokens;private int pos;
 private AccountCommand(List<Token> tokens){this.tokens=tokens;}
 private static final class Token {final String value;final char quote;Token(String v,char q){value=v;quote=q;}}
 public static boolean candidate(String sql){
  List<Token> t=lex(sql,2);if(t.isEmpty()||t.get(0).quote!=0)return false;
  String a=t.get(0).value.toUpperCase(Locale.ROOT),b=t.size()>1&&t.get(1).quote==0?t.get(1).value.toUpperCase(Locale.ROOT):"";
  return a.equals("GRANT")||a.equals("REVOKE")||Arrays.asList("CREATE","ALTER","DROP","RENAME").contains(a)&&b.equals("USER")||a.equals("SET")&&b.equals("PASSWORD");
 }
 public static AccountCommand parse(String sql){
  if(sql==null||sql.length()>16384)throw invalid();AccountCommand c=new AccountCommand(lex(sql,Integer.MAX_VALUE));
  if(c.take("CREATE")){
   c.operation="CREATE";c.expect("USER");if(c.take("IF")){c.expect("NOT");c.expect("EXISTS");c.conditional=true;}
   c.user();c.expect("IDENTIFIED");c.expect("BY");c.password=c.secret();
  }else if(c.take("ALTER")){
   c.operation="PASSWORD";c.expect("USER");c.user();c.expect("IDENTIFIED");c.expect("BY");c.password=c.secret();
  }else if(c.take("SET")){
   c.operation="PASSWORD";c.expect("PASSWORD");c.expect("FOR");c.user();c.expect("=");c.expect("PASSWORD");c.expect("(");c.password=c.secret();c.expect(")");
  }else if(c.take("DROP")){
   c.operation="DROP";c.expect("USER");if(c.take("IF")){c.expect("EXISTS");c.conditional=true;}c.user();
  }else{
   boolean grant=c.take("GRANT");if(!grant)c.expect("REVOKE");c.operation=grant?"GRANT":"REVOKE";
   do{c.word();}while(c.take(","));c.expect("ON");c.scope();c.expect(".");c.scope();if(c.take("."))c.scope();
   c.expect(grant?"TO":"FROM");c.user();
  }
  c.take(";");if(c.pos!=c.tokens.size())throw invalid();
  if(c.password!=null&&(c.password.isEmpty()||c.password.length()>512))throw invalid();
  return c;
 }
 private void user(){Token t=next();username=AccountName.validate(t.value);
  if(take("@")){if(!next().value.equals("%"))throw new IllegalArgumentException("自动接入仅支持 user@'%' 身份，其他 Host 身份不能自动合并。");}}
 private void scope(){if(!take("*")){Token t=next();if(t.quote=='\''||t.quote=='"'||!t.value.matches("[\\p{L}\\p{N}_$]+"))throw invalid();}}
 private void word(){Token t=next();if(t.quote!=0||!t.value.matches("[A-Za-z_]+"))throw invalid();}
 private String secret(){Token t=next();if(t.quote!='\'')throw new IllegalArgumentException("自动注册需要单引号普通密码，不支持摘要、外部认证或占位符。");return t.value;}
 private Token next(){if(pos>=tokens.size())throw invalid();return tokens.get(pos++);}
 private boolean take(String v){if(pos<tokens.size()&&tokens.get(pos).quote==0&&tokens.get(pos).value.equalsIgnoreCase(v)){pos++;return true;}return false;}
 private void expect(String v){if(!take(v))throw invalid();}
 private static IllegalArgumentException invalid(){return new IllegalArgumentException("不支持的账号语法：仅支持单用户 CREATE/ALTER USER ... IDENTIFIED BY、SET PASSWORD FOR ... = PASSWORD(...)、DROP USER、GRANT/REVOKE 权限列表 ON 库.表 TO/FROM 用户；不支持角色、多语句或密码摘要。");}
 private static List<Token> lex(String sql,int limit){
  if(sql==null)throw invalid();List<Token> out=new ArrayList<>();
  for(int i=0;i<sql.length()&&out.size()<limit;){
   char ch=sql.charAt(i);if(Character.isWhitespace(ch)||ch=='\uFEFF'){i++;continue;}
   if(ch=='/'&&i+1<sql.length()&&sql.charAt(i+1)=='*'){
    if(i+2<sql.length()&&sql.charAt(i+2)=='!')throw new IllegalArgumentException("账号管理不支持可执行注释。");
    int end=sql.indexOf("*/",i+2);if(end<0)throw invalid();i=end+2;continue;
   }
   if(ch=='#'||ch=='-'&&i+2<sql.length()&&sql.charAt(i+1)=='-'&&Character.isWhitespace(sql.charAt(i+2))){
    while(i<sql.length()&&sql.charAt(i)!='\n'&&sql.charAt(i)!='\r')i++;continue;
   }
   if(ch=='\''||ch=='"'||ch==96){
    char quote=ch;i++;StringBuilder s=new StringBuilder();boolean closed=false;
    while(i<sql.length()){
     ch=sql.charAt(i++);
     if(ch==quote){if(i<sql.length()&&sql.charAt(i)==quote){s.append(ch);i++;}else{closed=true;break;}}
     else if(ch=='\\'){
      if(i>=sql.length())throw invalid();char e=sql.charAt(i++);
      switch(e){case 'n':s.append('\n');break;case 'r':s.append('\r');break;case 't':s.append('\t');break;case '0':s.append('\0');break;case 'b':s.append('\b');break;case 'Z':s.append((char)26);break;case '%':case '_':s.append('\\').append(e);break;default:s.append(e);}
     }else s.append(ch);
    }
    if(!closed)throw invalid();out.add(new Token(s.toString(),quote));continue;
   }
   if(Character.isLetterOrDigit(ch)||ch=='_'||ch=='$'){
    int start=i++;while(i<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i))||sql.charAt(i)=='_'||sql.charAt(i)=='$'))i++;
    out.add(new Token(sql.substring(start,i),(char)0));
   }else{out.add(new Token(String.valueOf(ch),(char)0));i++;}
  }
  return out;
 }
}
