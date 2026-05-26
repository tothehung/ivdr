package com.ivdr.config;

import com.ivdr.domain.auth.service.TenantContextService;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Ensures PostgreSQL RLS session variables are set on every connection checked out of the pool.
 * This intercepts Spring's auto-configured DataSource and wraps it in a TenantAwareDataSource.
 */
@Component
public class TenantDataSourceBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource && !(bean instanceof TenantAwareDataSource)) {
            return new TenantAwareDataSource((DataSource) bean);
        }
        return bean;
    }

    private static class TenantAwareDataSource extends DelegatingDataSource {
        public TenantAwareDataSource(DataSource targetDataSource) {
            super(targetDataSource);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection conn = super.getConnection();
            setupSessionContext(conn);
            return conn;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection conn = super.getConnection(username, password);
            setupSessionContext(conn);
            return conn;
        }

        private void setupSessionContext(Connection conn) throws SQLException {
            UUID orgId = TenantContextService.getCurrentOrgId();
            UUID userId = TenantContextService.getCurrentUserId();

            // Always configure the connection. If orgId is null, both are set to null,
            // effectively clearing the session variable state on the connection to prevent bleed.
            try (PreparedStatement ps = conn.prepareStatement("SELECT set_session_context(?::uuid, ?::uuid)")) {
                ps.setObject(1, orgId);
                ps.setObject(2, userId);
                ps.execute();
            } catch (SQLException e) {
                // During startup and Flyway migrations, set_session_context might not exist yet.
                // We safely ignore this error only if orgId is null (which is true during migrations).
                if (orgId != null) {
                    throw e;
                }
            }
        }
    }
}
