package com.voyra.crm.integration;

import com.voyra.crm.AbstractIntegrationTest;
import com.voyra.crm.entity.NotificationOutbox;
import com.voyra.crm.enums.NotificationKind;
import com.voyra.crm.enums.NotificationOutboxStatus;
import com.voyra.crm.repository.NotificationOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DPDP outbox retention against real Postgres (the JPQL itself): FAILED bodies go by created_at, SENT bodies by sent_at,
 * and PENDING or SENDING rows older than the cutoff (SMTP off for a week) are given up as FAILED with their body dropped.
 * Needs Docker (Testcontainers), like the other *IT classes, so it is outside {@code mvn verify}.
 */
class NotificationOutboxRepositoryIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.now();
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(7));
    private static final Instant OLD = NOW.minus(Duration.ofDays(8));
    private static final Instant RECENT = NOW.minus(Duration.ofDays(1));

    @Autowired
    private NotificationOutboxRepository repository;

    @AfterEach
    void clean() {
        repository.deleteAll();
    }

    private void row(String id, NotificationOutboxStatus status, Instant createdAt, Instant sentAt) {
        repository.save(NotificationOutbox.builder().id(id).kind(NotificationKind.values()[0]).recipient("r@agency.test")
                .subject("s").bodyHtml("<p>resume link</p>").bodyText("resume link").status(status).attempts(0)
                .nextAttemptAt(createdAt).createdAt(createdAt).sentAt(sentAt).build());
    }

    private NotificationOutbox get(String id) {
        return repository.findById(id).orElseThrow();
    }

    @Test
    void oldBodiesAreClearedByTheRightClockAndStaleUnsentRowsExpire() {
        row("failed-old", NotificationOutboxStatus.FAILED, OLD, null);
        row("failed-new", NotificationOutboxStatus.FAILED, RECENT, null);
        row("sent-old", NotificationOutboxStatus.SENT, OLD, OLD);
        row("sent-created-old-sent-new", NotificationOutboxStatus.SENT, OLD, RECENT);
        row("pending-old", NotificationOutboxStatus.PENDING, OLD, null);
        row("sending-old", NotificationOutboxStatus.SENDING, OLD, null);
        row("pending-new", NotificationOutboxStatus.PENDING, RECENT, null);

        assertThat(repository.expireUnsent(CUTOFF)).isEqualTo(2);
        assertThat(repository.clearOldBodies(CUTOFF)).isEqualTo(2);

        for (String gone : new String[] {"failed-old", "sent-old", "pending-old", "sending-old"}) {
            assertThat(get(gone).getBodyHtml()).as(gone).isNull();
            assertThat(get(gone).getBodyText()).as(gone).isNull();
        }
        for (String kept : new String[] {"failed-new", "sent-created-old-sent-new", "pending-new"}) {
            assertThat(get(kept).getBodyHtml()).as(kept).isNotNull();
        }
        assertThat(get("pending-old").getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
        assertThat(get("sending-old").getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
        assertThat(get("pending-old").getLastError()).containsIgnoringCase("expired");
        assertThat(get("pending-new").getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(get("sent-old").getStatus()).isEqualTo(NotificationOutboxStatus.SENT);
    }
}
