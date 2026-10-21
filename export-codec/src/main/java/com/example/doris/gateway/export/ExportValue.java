package com.example.doris.gateway.export;

import java.math.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Lossless scalar export representation. Never uses arbitrary Object.toString(). */
public final class ExportValue {
    private ExportValue() { }
    public static List<String> encode(Object value) {
        if(value==null)return Arrays.asList("null",null);
        if(value instanceof String)return Arrays.asList("text",(String)value);
        if(value instanceof Boolean)return Arrays.asList("boolean",value.toString());
        if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof BigInteger)return Arrays.asList("integer",value.toString());
        if(value instanceof BigDecimal)return Arrays.asList("decimal",((BigDecimal)value).toPlainString());
        if(value instanceof Float)return Arrays.asList("float",Float.toString((Float)value));
        if(value instanceof Double)return Arrays.asList("double",Double.toString((Double)value));
        if(value instanceof java.sql.Date)return Arrays.asList("date",((java.sql.Date)value).toLocalDate().toString());
        if(value instanceof LocalDate)return Arrays.asList("date",value.toString());
        if(value instanceof Timestamp)return Arrays.asList("datetime",((Timestamp)value).toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace('T',' '));
        if(value instanceof LocalDateTime)return Arrays.asList("datetime",((LocalDateTime)value).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace('T',' '));
        if(value instanceof Time)return Arrays.asList("time",((Time)value).toLocalTime().withNano((int)Math.floorMod(((Time)value).getTime(),1000L)*1000000).format(DateTimeFormatter.ISO_LOCAL_TIME));
        if(value instanceof LocalTime)return Arrays.asList("time",((LocalTime)value).format(DateTimeFormatter.ISO_LOCAL_TIME));
        if(value instanceof Duration)return Arrays.asList("duration",value.toString());
        if(value instanceof OffsetDateTime)return Arrays.asList("offset-datetime",value.toString());
        if(value instanceof OffsetTime)return Arrays.asList("offset-time",value.toString());
        if(value instanceof ZonedDateTime)return Arrays.asList("zoned-datetime",value.toString());
        if(value instanceof Instant)return Arrays.asList("instant",value.toString());
        if(value instanceof byte[])return Arrays.asList("binary-base64",Base64.getEncoder().encodeToString((byte[])value));
        throw new IllegalArgumentException("没有稳定导出编码的 Java 类型："+value.getClass().getName());
    }
    public static String text(Object value) {return decode(encode(value));}
    public static String decode(Object cell) {
        if(!(cell instanceof List)||((List<?>)cell).size()!=2)throw new IllegalArgumentException("类型化单元格结构无效。");
        List<?> pair=(List<?>)cell;Object type=pair.get(0),value=pair.get(1);
        if("null".equals(type)&&value==null)return "";
        if(!(type instanceof String)||!(value instanceof String))throw new IllegalArgumentException("类型化单元格内容无效。");
        String s=(String)value;
        try {
            switch((String)type){
                case "text":return s;
                case "boolean":if(!"true".equals(s)&&!"false".equals(s))throw new IllegalArgumentException();break;
                case "integer":new BigInteger(s);break;
                case "decimal":new BigDecimal(s);break;
                case "float":Float.parseFloat(s);break;
                case "double":Double.parseDouble(s);break;
                case "date":LocalDate.parse(s);break;
                case "datetime":LocalDateTime.parse(s.replace(' ','T'));break;
                case "time":LocalTime.parse(s);break;
                case "duration":Duration.parse(s);break;
                case "offset-datetime":OffsetDateTime.parse(s);break;
                case "offset-time":OffsetTime.parse(s);break;
                case "instant":Instant.parse(s);break;
                case "zoned-datetime":ZonedDateTime.parse(s);break;
                case "binary-base64":Base64.getDecoder().decode(s);break;
                default:throw new IllegalArgumentException();
            }
        } catch(RuntimeException e){throw new IllegalArgumentException("类型化单元格编码无效："+type);}
        return s;
    }
    /** Retain immutable values, detach mutable scalars, and charge before allocating copies. */
    public static Object snapshot(Object value,Budget budget) throws SQLException {
        budget.charge(24);
        if(value==null)return null;
        if(value instanceof String){budget.charge(2L*((String)value).length());return value;}
        if(value instanceof byte[]){budget.charge(((byte[])value).length);return ((byte[])value).clone();}
        if(value instanceof Timestamp){Timestamp copy=new Timestamp(((Timestamp)value).getTime());copy.setNanos(((Timestamp)value).getNanos());return copy;}
        if(value instanceof java.sql.Date)return new java.sql.Date(((java.sql.Date)value).getTime());
        if(value instanceof Time)return new Time(((Time)value).getTime());
        if(value instanceof BigInteger){budget.charge(((BigInteger)value).bitLength()/8L+1);return value;}
        if(value instanceof BigDecimal){budget.charge(((BigDecimal)value).unscaledValue().bitLength()/8L+16);return value;}
        if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof Float||value instanceof Double||value instanceof Boolean||value instanceof LocalDate||value instanceof LocalTime||value instanceof LocalDateTime||value instanceof OffsetDateTime||value instanceof OffsetTime||value instanceof Instant||value instanceof ZonedDateTime||value instanceof Duration)return value;
        // Driver-owned LOBs/arrays require a protocol-specific detach strategy, never stringify silently.
        throw new SQLException("结果值无法安全缓冲，Java 类型："+value.getClass().getName(),"0A000");
    }
    public static final class Budget {
        private final long maximum;private long used;
        public Budget(long maximum){this.maximum=maximum;}
        public void charge(long bytes)throws SQLException{if(bytes<0||bytes>maximum-used)throw new SQLException("逐行加密结果缓冲超过 "+maximum+" 字节上限，请缩小查询范围。","54000");used+=bytes;}
        public long used(){return used;}
    }
}
