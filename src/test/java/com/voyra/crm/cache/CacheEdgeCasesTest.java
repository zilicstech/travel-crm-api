package com.voyra.crm.cache;

import com.voyra.crm.enums.SubscriptionPlan;
import com.voyra.crm.enums.SubscriptionStatus;
import com.voyra.crm.enums.TenantStatus;
import com.voyra.crm.models.Entitlements;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Null and blank ids are ignored everywhere, ids are case- and space-insensitive, and unknown ids read as absent. */
class CacheEdgeCasesTest {

    private static final Entitlements SUITE = new Entitlements(SubscriptionPlan.SUITE, SubscriptionStatus.ACTIVE, Set.of(), 5, false, null);

    @AfterEach
    void clean() {
        AgentCache.remove("CEA1");
        TenantCache.remove("CET1");
        TenantCache.remove("CET2");
    }

    @Test
    void agentCacheIgnoresMissingIdsAndMatchesIdsLoosely() {
        AgentCache.put(null, true);
        AgentCache.put(" ", true);
        AgentCache.revokeTokensBefore(null, 1);
        AgentCache.revokeTokensBefore(" ", 1);
        AgentCache.remove(null);
        assertThat(AgentCache.isActive(null)).isFalse();
        assertThat(AgentCache.isActive(" ")).isFalse();
        assertThat(AgentCache.isActive(" ", new Date())).isFalse();

        AgentCache.put(" cea1 ", true);
        assertThat(AgentCache.isActive("CEA1")).isTrue();
        assertThat(AgentCache.isActive("cea1", null)).as("no reset recorded: any token").isTrue();

        AgentCache.revokeTokensBefore("cea1", 100);
        assertThat(AgentCache.isActive("CEA1", null)).as("a token without an issue time cannot prove it is newer").isFalse();
        assertThat(AgentCache.isActive("CEA1", Date.from(Instant.ofEpochSecond(100)))).isTrue();

        AgentCache.remove(" cea1");
        assertThat(AgentCache.isActive("CEA1")).isFalse();
    }

    @Test
    void agentEntryWithoutAResetTimeHasNone() {
        assertThat(new AgentCache.AgentEntry("A1", true).passwordChangedAt()).isNull();
    }

    @Test
    void platformAdminCacheIgnoresMissingIds() {
        PlatformAdminCache.put(null, true);
        PlatformAdminCache.put("", true);
        assertThat(PlatformAdminCache.isActive(null)).isFalse();
        assertThat(PlatformAdminCache.isActive(" ")).isFalse();

        PlatformAdminCache.loadFrom(List.of(new PlatformAdminCache.AdminEntry(" cep1 ", true)));
        assertThat(PlatformAdminCache.isActive("CEP1")).isTrue();
        assertThat(PlatformAdminCache.isActive("CEP2")).isFalse();
    }

    @Test
    void tenantCacheIgnoresMissingIdsAndReadsUnknownOnesAsEmpty() {
        TenantCache.put(null, "x", TenantStatus.ACTIVE);
        TenantCache.put(" ", "x", TenantStatus.ACTIVE);
        TenantCache.putEntitlements(null, SUITE);
        TenantCache.putEntitlements(" ", SUITE);
        TenantCache.remove(null);
        TenantCache.remove(" ");
        for (String id : new String[]{null, " "}) {
            assertThat(TenantCache.entitlements(id)).isEmpty();
            assertThat(TenantCache.entitlementsAt(id)).isEmpty();
            assertThat(TenantCache.status(id)).isEmpty();
        }
        assertThat(TenantCache.entitlements("CET-UNKNOWN")).isEmpty();
        assertThat(TenantCache.entitlementsAt("CET-UNKNOWN")).isEmpty();
        assertThat(TenantCache.isValidTenant("CET-UNKNOWN")).isFalse();
    }

    @Test
    void entitlementsCachedBeforeTheTenantKeepTheirTimeAndSurviveARename() {
        TenantCache.putEntitlements("cet1", SUITE);
        assertThat(TenantCache.status("CET1")).isEmpty();
        assertThat(TenantCache.entitlementsAt("CET1")).hasValueSatisfying(at -> assertThat(at).isCloseTo(Instant.now(), within(1, java.time.temporal.ChronoUnit.MINUTES)));

        TenantCache.put("CET1", null, TenantStatus.ACTIVE);
        assertThat(TenantCache.entitlements("CET1")).contains(SUITE);
        assertThat(TenantCache.status("CET1")).contains(TenantStatus.ACTIVE);

        TenantCache.putEntitlements("CET1", null);
        assertThat(TenantCache.entitlements("CET1")).isEmpty();
        assertThat(TenantCache.status("CET1")).as("an entitlements refresh keeps the status").contains(TenantStatus.ACTIVE);
    }

    @Test
    void backdatingMovesOnlyAKnownTenantsEntitlementsTime() {
        TenantCache.loadFrom(List.of(new TenantCache.TenantEntry("CET2", "B", TenantStatus.ACTIVE)));
        TenantCache.putEntitlements("CET2", SUITE);

        TenantCache.backdateEntitlements("CET2", Duration.ofHours(2));
        TenantCache.backdateEntitlements("CET-UNKNOWN", Duration.ofHours(2));

        assertThat(TenantCache.entitlementsAt("CET2")).hasValueSatisfying(at ->
                assertThat(at).isCloseTo(Instant.now().minus(Duration.ofHours(2)), within(1, java.time.temporal.ChronoUnit.MINUTES)));
        assertThat(TenantCache.status("CET-UNKNOWN")).isEmpty();
    }

    @Test
    void placeNameKeepsTheFirstOfEquallyRankedAirportsInACity() {
        assertThat(AirportCache.findByPlaceName("new york").iata()).isEqualTo("JFK");
    }

    @Test
    void placeNamePrefixMatchesACityOrAnAirportNameAndKeepsTheFirst() {
        assertThat(AirportCache.findByPlaceName("Chhatrapati Shivaji").iata()).isEqualTo("BOM");
        AirportCache.Airport lond = AirportCache.findByPlaceName("Lond");
        assertThat(lond.city().toUpperCase()).startsWith("LOND");
        assertThat(AirportCache.countries()).containsEntry("IN", "India");
    }
}
