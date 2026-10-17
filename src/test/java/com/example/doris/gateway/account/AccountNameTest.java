package com.example.doris.gateway.account;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AccountNameTest {
 @Test void unicodeAccountLifecycle(){for(String user:new String[]{"中文用户","关系网_测试01","用户.a-b"}){
  assertEquals(user,AccountName.validate(user));
  assertEquals(user,AccountCommand.parse("CREATE USER '"+user+"'@'%' IDENTIFIED BY 'example-only'").username);
  assertEquals(user,AccountCommand.parse("GRANT SELECT_PRIV ON db.* TO '"+user+"'").username);
  assertEquals(user,AccountCommand.parse("ALTER USER '"+user+"' IDENTIFIED BY 'example-only'").username);
  assertEquals(user,AccountCommand.parse("DROP USER '"+user+"'").username);
 }}
 @Test void refuseMappingDelimitersAndSilentChanges(){for(String user:new String[]{""," 中文","中文 ","a@b","a,b","a=b","a\n", "a\u0000b"})assertThrows(IllegalArgumentException.class,()->AccountName.validate(user));}
}
