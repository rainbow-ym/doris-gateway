package com.example.doris.gateway.account;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AccountCommandTest {
 @Test void commentsAndSecrets(){
  AccountCommand c=AccountCommand.parse("/* client DBeaver */ CREATE /*x*/ USER 'new_user'@'%' IDENTIFIED BY 'a;-- /*b*/ ''x' ; -- end\n");
  assertEquals("CREATE",c.operation);assertEquals("new_user",c.username);assertEquals("a;-- /*b*/ 'x",c.password);
  assertTrue(AccountCommand.candidate("# client\nGRANT SELECT_PRIV ON db.* TO new_user"));
  assertFalse(AccountCommand.candidate("SELECT 'CREATE USER'"));
 }
 @Test void operations(){
  assertEquals("GRANT",AccountCommand.parse("GRANT SELECT_PRIV,LOAD_PRIV ON DWD_人口.人口关系网 TO 'tester'").operation);
  assertEquals("REVOKE",AccountCommand.parse("REVOKE SELECT_PRIV ON internal.db.* FROM tester").operation);
  assertEquals("PASSWORD",AccountCommand.parse("SET PASSWORD FOR tester = PASSWORD('a\\\\b')").operation);
  assertEquals("a\\b",AccountCommand.parse("ALTER USER tester IDENTIFIED BY 'a\\\\b'").password);
  assertTrue(AccountCommand.parse("DROP USER IF EXISTS tester").conditional);
 }
 @Test void refuseAmbiguity(){
  for(String sql:new String[]{"CREATE USER tester IDENTIFIED BY 'x'; GRANT ALL ON *.* TO tester",
   "GRANT roleA TO tester","GRANT SELECT_PRIV ON db.* TO tester WITH GRANT OPTION",
   "CREATE USER tester@'localhost' IDENTIFIED BY 'x'","CREATE USER tester IDENTIFIED BY PASSWORD 'hash'",
   "CREATE USER tester IDENTIFIED BY ?","CREATE USER tester IDENTIFIED BY 'x' /*! grant all */",
   "DROP USER a,b","RENAME USER a TO b","CREATE USER tester IDENTIFIED BY 'unterminated"})
   assertThrows(IllegalArgumentException.class,()->AccountCommand.parse(sql),sql);
 }
}
