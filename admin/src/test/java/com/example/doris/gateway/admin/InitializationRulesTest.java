package com.example.doris.gateway.admin;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InitializationRulesTest {
    @Test void unicodeCsvAndSpecificErrors(){
        assertEquals("中文用户",AccountCsv.read("username,password\n中文用户,example-only").get(0).get("username"));
        String duplicate=assertThrows(IllegalArgumentException.class,()->AccountCsv.read("username,password\n中文用户,secret1\n中文用户,secret2")).getMessage();
        assertTrue(duplicate.contains("3")&&duplicate.contains("2")&&duplicate.contains("重复"));assertFalse(duplicate.contains("secret"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->AccountCsv.read("username,password\nUser,secret1\nuser,secret2")).getMessage().contains("大小写"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->AccountCsv.read("username,password\n,secret1")).getMessage().contains("不能为空"));
    }
    @Test void csvPreservesQuotedPasswordsAndDefaultsScope(){
        List<Map<String,String>> r=AccountCsv.read("\uFEFFusername,password,databasePattern\r\nbob,\"p,\"\"q\",\r\n");
        assertEquals("p,\"q",r.get(0).get("password"));assertEquals("%",r.get(0).get("databasePattern"));
    }
    @Test void rejectsDuplicateOrMalformedCsv(){
        assertThrows(IllegalArgumentException.class,()->AccountCsv.read("username,password\nbob,1\nBOB,2"));
        assertThrows(IllegalArgumentException.class,()->AccountCsv.read("username,password\nbob,\"broken"));
        assertThrows(IllegalArgumentException.class,()->AccountCsv.read("username,password,unknown\nbob,1,x"));
    }
    @Test void fieldRulesAreScopedAndExactWins(){
        FieldMappingRules a=rules(),b=rules();a.save(rule("EXACT","关系人证件号码","ID_CARD","DWD_人口"));
        assertEquals("ID_CARD",a.recommend("DWD_人口","关系网","关系人证件号码"));
        assertNull(a.recommend("OTHER","关系网","关系人证件号码"));assertNull(b.recommend("DWD_人口","关系网","关系人证件号码"));
        a.save(rule("SUFFIX","证件号码","PERSON_NAME","*"));assertEquals("ID_CARD",a.recommend("DWD_人口","关系网","关系人证件号码"));
    }
    @Test void equalPriorityConflictIsRejected(){
        FieldMappingRules r=rules();r.save(rule("PREFIX","关系人","PERSON_NAME","*"));r.save(rule("SUFFIX","证件号码","ID_CARD","*"));
        assertThrows(IllegalArgumentException.class,()->r.recommend("db","t","关系人证件号码"));
    }
    private FieldMappingRules rules(){DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1","sa","");FieldMappingRules r=new FieldMappingRules(new JdbcTemplate(ds));r.initialize();return r;}
    private Map<String,Object> rule(String kind,String expression,String type,String db){Map<String,Object> r=new LinkedHashMap<>();r.put("matchKind",kind);r.put("expression",expression);r.put("sensitiveType",type);r.put("databaseName",db);return r;}
}
