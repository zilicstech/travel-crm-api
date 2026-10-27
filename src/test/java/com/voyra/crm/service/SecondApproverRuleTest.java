package com.voyra.crm.service;

import com.voyra.crm.entity.PlatformAuditLog;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.repository.PlatformAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Final review Important 2: one person must not satisfy a two-admin rule alone by adding a second admin login. The approver
 * is refused when either admin's login was added by the other (directly or through admins they added), or when the
 * approver's login was added after the request.
 */
class SecondApproverRuleTest {

    private static final Instant REQUESTED = Instant.parse("2026-10-20T10:00:00Z");

    private final PlatformAuditLogRepository auditLog = mock(PlatformAuditLogRepository.class);
    private final SecondApproverRule rule = new SecondApproverRule(auditLog);
    /** admin id -> (creator id, created at), as PLATFORM_ADMIN_CREATE audit entries. */
    private final Map<String, PlatformAuditLog> created = new HashMap<>();

    @BeforeEach
    void setUp() {
        lenient().when(auditLog.findFirstByActionAndEntityIdOrderByCreatedAtAsc(eq(PlatformAuditAction.PLATFORM_ADMIN_CREATE), anyString()))
                .thenAnswer(i -> Optional.ofNullable(created.get(i.<String>getArgument(1))));
        // the seeded admin S has no create entry; S added A and B, two separate people, before the request
        added("A", "S", REQUESTED.minus(30, ChronoUnit.DAYS));
        added("B", "S", REQUESTED.minus(20, ChronoUnit.DAYS));
    }

    private void added(String id, String by, Instant at) {
        created.put(id, PlatformAuditLog.builder().id("L-" + id).actorId(by).action(PlatformAuditAction.PLATFORM_ADMIN_CREATE)
                .entityType("PLATFORM_ADMIN").entityId(id).createdAt(at).build());
    }

    @Test
    void twoAdminsAddedBySomeoneElseBeforeTheRequestMayPair() {
        assertThatCode(() -> rule.requireIndependent("B", "A", REQUESTED)).doesNotThrowAnyException();
    }

    @Test
    void theRequestersOwnCreationIsRefused() {
        added("C", "A", REQUESTED.minus(1, ChronoUnit.DAYS)); // A added C, then C approves A's request
        assertThatThrownBy(() -> rule.requireIndependent("C", "A", REQUESTED))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("independent");
    }

    @Test
    void theRequestersCreatorIsRefused() {
        // S added A; S approving A's request is the other direction
        assertThatThrownBy(() -> rule.requireIndependent("S", "A", REQUESTED))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("independent");
    }

    @Test
    void aLoginAddedThroughAnotherAddedLoginIsRefused() {
        added("C", "A", REQUESTED.minus(2, ChronoUnit.DAYS));
        added("D", "C", REQUESTED.minus(1, ChronoUnit.DAYS)); // A -> C -> D: D's login is still A's doing
        assertThatThrownBy(() -> rule.requireIndependent("D", "A", REQUESTED)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anApproverAddedAfterTheRequestIsRefused() {
        added("E", "B", REQUESTED.plus(1, ChronoUnit.HOURS)); // not linked to A, but added after A asked
        assertThatThrownBy(() -> rule.requireIndependent("E", "A", REQUESTED))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("added after");
    }
}
