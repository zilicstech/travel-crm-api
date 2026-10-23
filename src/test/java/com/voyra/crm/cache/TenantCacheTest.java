package com.voyra.crm.cache;

import com.voyra.crm.enums.TenantStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SUSPENDED agencies still authenticate (read-only is enforced later); only DEACTIVATED is shut out. */
class TenantCacheTest {

    @Test
    void suspendedTenantIsStillValidForTheFilter() {
        TenantCache.put("t1", "A", TenantStatus.SUSPENDED);
        assertTrue(TenantCache.isValidTenant("t1"));
        assertEquals(Optional.of(TenantStatus.SUSPENDED), TenantCache.status("t1"));
    }

    @Test
    void deactivatedTenantIsInvalid() {
        TenantCache.put("t2", "B", TenantStatus.DEACTIVATED);
        assertFalse(TenantCache.isValidTenant("t2"));
    }
}
