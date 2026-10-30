package com.voyra.crm.config;

import com.voyra.crm.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Every borrowed connection gets the search_path of the tenant in context at borrow time; the rest delegates. */
@ExtendWith(MockitoExtension.class)
class TenantSchemaDataSourceTest {

    @Mock private DataSource delegate;
    @Mock private Connection connection;
    @Mock private Statement statement;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aConnectionIsPointedAtTheTenantSchemaInContext() throws Exception {
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        TenantContext.setTenantId("AB-12");

        assertThat(new TenantSchemaDataSource(delegate).getConnection()).isSameAs(connection);

        verify(statement).execute("SET search_path TO tenant_ab12, public");
        verify(statement).close();
    }

    @Test
    void aConnectionWithCredentialsWithoutATenantUsesPublic() throws Exception {
        when(delegate.getConnection("u", "p")).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        assertThat(new TenantSchemaDataSource(delegate).getConnection("u", "p")).isSameAs(connection);

        verify(statement).execute("SET search_path TO public");
    }

    @Test
    void logWriterTimeoutAndParentLoggerDelegate() throws Exception {
        PrintWriter writer = new PrintWriter(new StringWriter());
        Logger parent = Logger.getLogger("test");
        when(delegate.getLogWriter()).thenReturn(writer);
        when(delegate.getLoginTimeout()).thenReturn(7);
        when(delegate.getParentLogger()).thenReturn(parent);
        TenantSchemaDataSource ds = new TenantSchemaDataSource(delegate);

        ds.setLogWriter(writer);
        ds.setLoginTimeout(7);

        verify(delegate).setLogWriter(writer);
        verify(delegate).setLoginTimeout(7);
        assertThat(ds.getLogWriter()).isSameAs(writer);
        assertThat(ds.getLoginTimeout()).isEqualTo(7);
        assertThat(ds.getParentLogger()).isSameAs(parent);
    }

    @Test
    void unwrapAnswersForItselfFirstThenAsksTheDelegate() throws Exception {
        TenantSchemaDataSource ds = new TenantSchemaDataSource(delegate);
        when(delegate.unwrap(Connection.class)).thenReturn(connection);
        when(delegate.isWrapperFor(Connection.class)).thenReturn(false);
        when(delegate.isWrapperFor(AutoCloseable.class)).thenReturn(true);

        assertThat(ds.unwrap(TenantSchemaDataSource.class)).isSameAs(ds);
        assertThat(ds.unwrap(Connection.class)).isSameAs(connection);
        assertThat(ds.isWrapperFor(DataSource.class)).isTrue();
        assertThat(ds.isWrapperFor(Connection.class)).isFalse();
        assertThat(ds.isWrapperFor(AutoCloseable.class)).isTrue();
    }
}
