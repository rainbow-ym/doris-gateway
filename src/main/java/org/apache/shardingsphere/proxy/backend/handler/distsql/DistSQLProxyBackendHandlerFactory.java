/* Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. The ASF licenses this file to You under
 * the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.shardingsphere.proxy.backend.handler.distsql;

import org.apache.shardingsphere.authority.rule.AuthorityRule;
import org.apache.shardingsphere.infra.metadata.user.ShardingSphereUser;
import org.apache.shardingsphere.distsql.statement.DistSQLStatement;
import org.apache.shardingsphere.distsql.statement.type.ral.RALStatement;
import org.apache.shardingsphere.distsql.statement.type.ral.queryable.QueryableRALStatement;
import org.apache.shardingsphere.distsql.statement.type.rdl.RDLStatement;
import org.apache.shardingsphere.distsql.statement.type.rql.RQLStatement;
import org.apache.shardingsphere.distsql.statement.type.rul.RULStatement;
import org.apache.shardingsphere.infra.exception.generic.UnsupportedSQLOperationException;
import org.apache.shardingsphere.infra.session.query.QueryContext;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.backend.handler.ProxyBackendHandler;
import org.apache.shardingsphere.proxy.backend.session.ConnectionSession;

/** 5.5.3 handler with a control-account boundary for DistSQL administration. */
public final class DistSQLProxyBackendHandlerFactory {
    private DistSQLProxyBackendHandlerFactory() { }
    public static ProxyBackendHandler newInstance(DistSQLStatement statement,QueryContext queryContext,ConnectionSession session) {
        if(statement instanceof com.example.doris.gateway.account.AccountLifecycleStatement)
            return () -> {
                if(!queryContext.getParameters().isEmpty()) throw new java.sql.SQLException("账号管理不支持参数占位符。","0A000");
                String actor=session.getConnectionContext().getGrantee().getUsername();
                try {
                    String response=com.example.doris.gateway.lineage.LineageEventEmitter.accountCommand(actor,session.getCurrentDatabaseName(),queryContext.getSql());
                    java.util.Map<?,?> data=new com.fasterxml.jackson.databind.ObjectMapper().readValue(response,java.util.Map.class);
                    if(data.get("epoch") instanceof Number)com.example.doris.gateway.security.UserBackendConnections.observeEpoch(String.valueOf(data.get("username")),((Number)data.get("epoch")).longValue());
                    return new org.apache.shardingsphere.proxy.backend.response.header.update.UpdateResponseHeader(statement);
                } catch(IllegalStateException ex) {throw new java.sql.SQLException(ex.getMessage(),"HY000");}
                catch(Exception ex){throw new java.sql.SQLException("账号操作结果需核实，请查看账号同步任务，不要重复执行。","HY000");}
            };
        ContextManager context=ProxyContext.getInstance().getContextManager();
        AuthorityRule authority=context.getMetaDataContexts().getMetaData().getGlobalRuleMetaData().getSingleRule(AuthorityRule.class);
        // Ordinary SHOW / information_schema remain unchanged; DistSQL exports may contain credentials.
        if(!authority.findUser(session.getConnectionContext().getGrantee()).map(ShardingSphereUser::isAdmin).orElse(false))
            throw new UnsupportedSQLOperationException("网关配置命令仅允许平台控制账号执行，请使用管理页面。");
        if(statement instanceof RQLStatement || statement instanceof RULStatement)
            return new DistSQLQueryProxyBackendHandler(statement,queryContext,session,context);
        if(statement instanceof RDLStatement) return new DistSQLUpdateProxyBackendHandler(statement,queryContext,session,context);
        if(statement instanceof RALStatement) return statement instanceof QueryableRALStatement
                ?new DistSQLQueryProxyBackendHandler(statement,queryContext,session,context)
                :new DistSQLUpdateProxyBackendHandler(statement,queryContext,session,context);
        throw new UnsupportedSQLOperationException(statement.getClass().getName());
    }
}
