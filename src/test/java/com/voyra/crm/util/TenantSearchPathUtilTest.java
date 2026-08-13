package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The allowlist regex here is the only defence against SQL injection through the tenant
 * identifier (blueprint §3.3) - the schema name is concatenated directly into a SET
 * search_path statement and cannot be a bind parameter.
 */
class TenantSearchPathUtilTest {

    @Test
    void validTenantIdProducesLowercasedSchemaPath() {
        assertThat(TenantSearchPathUtil.toSchemaPath("ABC123")).isEqualTo("tenant_abc123, public");
    }

    @Test
    void nullTenantIdFallsBackToPublic() {
        assertThat(TenantSearchPathUtil.toSchemaPath(null)).isEqualTo("public");
    }

    @Test
    void blankTenantIdFallsBackToPublic() {
        assertThat(TenantSearchPathUtil.toSchemaPath("")).isEqualTo("public");
    }

    @Test
    void sqlInjectionAttemptViaSemicolonIsRejected() {
        assertThat(TenantSearchPathUtil.toSchemaPath("abc; DROP TABLE tenant")).isEqualTo("public");
    }

    @Test
    void sqlInjectionAttemptViaQuoteIsRejected() {
        assertThat(TenantSearchPathUtil.toSchemaPath("abc'--")).isEqualTo("public");
    }
}
