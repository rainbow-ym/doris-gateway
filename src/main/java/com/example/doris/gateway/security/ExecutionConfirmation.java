package com.example.doris.gateway.security;

import java.sql.SQLException;
import java.util.UUID;

/** Separates database execution from acknowledgement; never retries business SQL. */
public final class ExecutionConfirmation {
    private ExecutionConfirmation() { }
    @FunctionalInterface public interface Operation<T> { T execute() throws SQLException; }
    @FunctionalInterface public interface Audit { void record(String requestId, String outcome); }

    public static <T> T execute(Operation<T> operation, Audit audit) throws SQLException {
        String id=UUID.randomUUID().toString();
        audit.record(id,"STARTED"); // Must persist before any business execution.
        T result;
        try { result=operation.execute(); }
        catch (SQLException | RuntimeException error) {
            // An exception from the execution pipeline is not proof of rollback.
            try { audit.record(id,"UNKNOWN"); } catch (RuntimeException auditError) { error.addSuppressed(auditError); }
            if (uncertain(error)) throw pending(id,"数据库执行结果或执行后同步待核实",error);
            throw error;
        }
        try { audit.record(id,"EXECUTED"); }
        catch (RuntimeException error) { throw pending(id,"数据库执行链已返回成功，但完成审计未确认",error); }
        return result;
    }

    private static SQLException pending(String id,String reason,Throwable cause) {
        return new SQLException(reason+"；不要自动重试业务 SQL，请凭请求编号核实："+id,"HY000",39001,cause);
    }

    public static boolean uncertain(Throwable error) {
        java.util.Set<Throwable> visited=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        java.util.Deque<Throwable> queue=new java.util.ArrayDeque<>();if(error!=null)queue.add(error);
        while(!queue.isEmpty()) {
            Throwable e=queue.remove();if(!visited.add(e))continue;
            if(e instanceof PostExecutionFailure || e instanceof java.sql.SQLTimeoutException
                    || e instanceof java.sql.SQLRecoverableException || e instanceof java.net.SocketException
                    || e instanceof java.net.SocketTimeoutException) return true;
            if(e instanceof SQLException) {
                SQLException sql=(SQLException)e;String state=sql.getSQLState();
                if(state!=null&&(state.startsWith("08")||state.equals("HYT00")||state.equals("HYT01")||state.equals("40003")))return true;
                if(sql.getNextException()!=null)queue.add(sql.getNextException());
            }
            if(e.getCause()!=null)queue.add(e.getCause());
        }
        return false;
    }

    public static final class PostExecutionFailure extends IllegalStateException {
        public PostExecutionFailure(String executionId,Throwable cause) {
            super("数据库语句已执行，保护同步确认失败；勿重复执行。执行编号："+executionId,cause);
        }
    }
}
