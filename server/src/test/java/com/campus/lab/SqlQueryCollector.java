package com.campus.lab;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;

@Intercepts(@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class}))
public class SqlQueryCollector implements Interceptor {

    private final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        statements.get().add(handler.getBoundSql().getSql());
        return invocation.proceed();
    }

    public void clear() {
        statements.get().clear();
    }

    public List<String> statements() {
        return List.copyOf(statements.get());
    }
}
