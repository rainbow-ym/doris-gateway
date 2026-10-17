package com.example.doris.gateway.admin;

import java.util.*;

/** Parses documented SHOW GRANTS scope columns; unrecognized forms remain unknown. */
final class DorisPrivilegeSnapshot {
    static final List<String> OPERATIONS=Arrays.asList("SELECT_PRIV","LOAD_PRIV","ALTER_PRIV","CREATE_PRIV","DROP_PRIV");
    static final class Scope {
        final String catalog,database,table,privilege,source;
        Scope(String catalog,String database,String table,String privilege,String source) {
            this.catalog=catalog;this.database=database;this.table=table;this.privilege=privilege;this.source=source;
        }
        boolean matches(String db,String name,String operation) {
            return ("*".equals(catalog)||"internal".equalsIgnoreCase(catalog))
                    && ("*".equals(database)||database.equals(db)) && ("*".equals(table)||table.equals(name)) && privilege.equals(operation);
        }
    }
    static List<Scope> parse(List<Map<String,Object>> rows) {
        List<Scope> result=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            String global=value(row,"GlobalPrivs");
            privileges(result,"*","*","*",global,"GLOBAL");
            scopes(result,value(row,"CatalogPrivs"),1,"CATALOG");
            scopes(result,value(row,"DatabasePrivs"),2,"DATABASE");
            scopes(result,value(row,"TablePrivs"),3,"TABLE");
        }
        return result;
    }
    private static void scopes(List<Scope> out,String text,int parts,String source) {
        for(String entry:text.split(";")) {
            int colon=entry.indexOf(':');if(colon<0)continue;
            String path=entry.substring(0,colon).trim();String[] names=path.split("\\.",-1);
            // Legacy cluster prefixes and quoted dotted names require an authoritative query.
            if(names.length!=parts)continue;
            privileges(out,names[0],parts>1?names[1]:"*",parts>2?names[2]:"*",entry.substring(colon+1),source);
        }
    }
    private static void privileges(List<Scope> out,String catalog,String db,String table,String text,String source) {
        for(String raw:text.split(",")) {
            String permission=raw.trim().replaceAll("\\([^)]*\\)$","").trim().toUpperCase(Locale.ROOT);
            if("ADMIN_PRIV".equals(permission))for(String operation:OPERATIONS)out.add(new Scope(catalog,db,table,operation,source));
            else if(OPERATIONS.contains(permission))out.add(new Scope(catalog,db,table,permission,source));
        }
    }
    private static String value(Map<String,Object> row,String key) {
        for(Map.Entry<String,Object> e:row.entrySet())if(key.equalsIgnoreCase(e.getKey()))return e.getValue()==null?"":String.valueOf(e.getValue());
        return "";
    }
}
