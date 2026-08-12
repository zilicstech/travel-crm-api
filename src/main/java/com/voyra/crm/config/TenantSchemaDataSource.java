package com.voyra.crm.config;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.util.TenantSearchPathUtil;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * Wraps the real DataSource so every connection borrowed from the pool has the correct
 * Postgres search_path applied for whichever tenant is active in {@link TenantContext} at
 * borrow time. This is why any code that switches tenants mid-request must do so in a new
 * transaction (REQUIRES_NEW) - a connection acquired before the switch keeps the old schema.
 */
public class TenantSchemaDataSource implements DataSource {

    private final DataSource delegate;

    public TenantSchemaDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection conn = delegate.getConnection();
        TenantSearchPathUtil.applyOnConnection(conn, TenantContext.getTenantId());
        return conn;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection conn = delegate.getConnection(username, password);
        TenantSearchPathUtil.applyOnConnection(conn, TenantContext.getTenantId());
        return conn;
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
