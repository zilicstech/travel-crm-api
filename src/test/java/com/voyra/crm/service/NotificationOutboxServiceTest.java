package com.voyra.crm.service;

import com.voyra.crm.entity.NotificationOutbox;
import com.voyra.crm.enums.NotificationKind;
import com.voyra.crm.enums.NotificationOutboxStatus;
import com.voyra.crm.repository.NotificationOutboxRepository;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationOutboxServiceTest {

    private static final NotificationTemplates.Email EMAIL =
            new NotificationTemplates.Email("Wallet low", "<p>Top up</p>", "Top up");

    @Mock
    private NotificationOutboxRepository repository;
    @Mock
    private JavaMailSender mailSender;

    private final FakeTransactionManager txManager = new FakeTransactionManager();
    private NotificationOutboxService outbox;

    @BeforeEach
    void setUp() {
        outbox = new NotificationOutboxService(repository, mailSender, txManager, "Voyra <no-reply@voyra.test>", 5);
        given(mailSender.createMimeMessage()).willAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    // ---- after-commit enqueue

    @Test
    void queuedOnlyAfterTheTransactionCommits() {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            outbox.enqueue(NotificationKind.LOW_BALANCE, List.of("owner@agency.test"), EMAIL);
            verify(repository, never()).saveAll(anyList());
        });

        List<NotificationOutbox> rows = savedRows();
        assertThat(rows).hasSize(1);
        NotificationOutbox row = rows.get(0);
        assertThat(row.getId()).isNotBlank();
        assertThat(row.getKind()).isEqualTo(NotificationKind.LOW_BALANCE);
        assertThat(row.getRecipient()).isEqualTo("owner@agency.test");
        assertThat(row.getSubject()).isEqualTo("Wallet low");
        assertThat(row.getBodyHtml()).isEqualTo("<p>Top up</p>");
        assertThat(row.getBodyText()).isEqualTo("Top up");
        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getNextAttemptAt()).isCloseTo(Instant.now(), within(5, java.time.temporal.ChronoUnit.SECONDS));
    }

    /** Review Focus 1: a wallet credit that rolled back must never email anyone. */
    @Test
    void aRolledBackTransactionQueuesNothing() {
        assertThatThrownBy(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
            outbox.enqueue(NotificationKind.TOPUP_APPROVED, List.of("owner@agency.test"), EMAIL);
            throw new IllegalStateException("credit failed");
        })).hasMessage("credit failed");

        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            outbox.enqueue(NotificationKind.TOPUP_APPROVED, List.of("owner@agency.test"), EMAIL);
            s.setRollbackOnly();
        });

        verifyNoInteractions(repository);
    }

    @Test
    void withoutATransactionItIsQueuedAtOnce() {
        outbox.enqueue(NotificationKind.PLATFORM_ALERT, List.of("a@voyra.test", "b@voyra.test"), EMAIL);

        assertThat(savedRows()).extracting(NotificationOutbox::getRecipient).containsExactly("a@voyra.test", "b@voyra.test");
    }

    @Test
    void oneRowPerDistinctRecipientAndBlanksAreSkipped() {
        outbox.enqueue(NotificationKind.PLATFORM_ALERT, Arrays.asList("a@voyra.test", " ", null, "a@voyra.test"), EMAIL);

        assertThat(savedRows()).extracting(NotificationOutbox::getRecipient).containsExactly("a@voyra.test");
    }

    @Test
    void noRecipientQueuesNothing() {
        outbox.enqueue(NotificationKind.LOW_BALANCE, List.of(), EMAIL);

        verifyNoInteractions(repository);
    }

    /** The business transaction already committed: a failure to queue is logged, never thrown at the caller. */
    @Test
    void aQueueFailureNeverReachesTheCaller() {
        given(repository.saveAll(anyList())).willThrow(new IllegalStateException("db down"));

        assertThatCode(() -> new TransactionTemplate(txManager).executeWithoutResult(s ->
                outbox.enqueue(NotificationKind.LOW_BALANCE, List.of("owner@agency.test"), EMAIL))).doesNotThrowAnyException();
    }

    // ---- sending

    @Test
    void aSentRowIsMarkedSentAndItsBodiesCleared() throws Exception {
        NotificationOutbox row = claimable("N1", 1);

        outbox.drainDue();

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        MimeMessage message = sent.getValue();
        assertThat(message.getSubject()).isEqualTo("Wallet low");
        assertThat(message.getRecipients(Message.RecipientType.TO)[0].toString()).isEqualTo("owner@agency.test");
        assertThat(message.getFrom()[0].toString()).contains("no-reply@voyra.test");
        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.SENT);
        assertThat(row.getSentAt()).isNotNull();
        assertThat(row.getBodyHtml()).isNull();
        assertThat(row.getBodyText()).isNull();
        verify(repository).save(row);
    }

    @Test
    void aFailedSendIsRetriedWithExponentialBackoff() {
        NotificationOutbox row = claimable("N1", 3);
        doThrow(new MailSendException("connection refused")).when(mailSender).send(any(MimeMessage.class));

        outbox.drainDue();

        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        // attempt 3 failed: wait 1 min x 2^(3-1)
        assertThat(row.getNextAttemptAt()).isCloseTo(Instant.now().plus(Duration.ofMinutes(4)), within(5, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(row.getLastError()).contains("MailSendException");
        assertThat(row.getBodyHtml()).isNotNull();
    }

    @Test
    void theBackoffDoublesEachAttempt() {
        Instant now = Instant.parse("2026-10-26T10:00:00Z");
        assertThat(NotificationOutboxService.nextAttempt(now, 1)).isEqualTo(now.plus(Duration.ofMinutes(1)));
        assertThat(NotificationOutboxService.nextAttempt(now, 2)).isEqualTo(now.plus(Duration.ofMinutes(2)));
        assertThat(NotificationOutboxService.nextAttempt(now, 4)).isEqualTo(now.plus(Duration.ofMinutes(8)));
    }

    @Test
    void theLastAllowedAttemptFailingMarksTheRowFailed() {
        NotificationOutbox row = claimable("N1", 5);
        doThrow(new MailSendException("mailbox unavailable")).when(mailSender).send(any(MimeMessage.class));

        outbox.drainDue();

        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
        assertThat(row.getLastError()).contains("MailSendException");
        verify(repository).save(row);
    }

    /** Two instances polling at once: the claim update succeeds for one of them only, so the email goes out once. */
    @Test
    void aRowIsSentOnceAcrossTwoPollers() {
        given(repository.findDueIds(any(), any())).willReturn(List.of("N1"));
        given(repository.claim(eq("N1"), any(), any())).willReturn(1, 0);
        given(repository.findById("N1")).willReturn(Optional.of(row("N1", 1)));
        NotificationOutboxService second = new NotificationOutboxService(repository, mailSender, txManager, "no-reply@voyra.test", 5);

        outbox.drainDue();
        second.drainDue();

        verify(repository, times(2)).claim(eq("N1"), any(), any());
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void theClaimLeasesTheRowSoACrashedSendIsRetriedLater() {
        claimable("N1", 1);

        outbox.drainDue();

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> lease = ArgumentCaptor.forClass(Instant.class);
        verify(repository).claim(eq("N1"), now.capture(), lease.capture());
        assertThat(Duration.between(now.getValue(), lease.getValue())).isEqualTo(NotificationOutboxService.LEASE);
    }

    @Test
    void oneBrokenRowDoesNotStopTheRest() {
        given(repository.findDueIds(any(), any())).willReturn(List.of("BAD", "N2"));
        given(repository.claim(eq("BAD"), any(), any())).willThrow(new IllegalStateException("db hiccup"));
        given(repository.claim(eq("N2"), any(), any())).willReturn(1);
        given(repository.findById("N2")).willReturn(Optional.of(row("N2", 1)));

        outbox.drainDue();

        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    void mailFromIsRequiredWhenSmtpIsOn() {
        assertThatThrownBy(() -> new NotificationOutboxService(repository, mock(JavaMailSender.class), txManager, " ", 5))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_FROM");
    }

    // ---- helpers

    @SuppressWarnings("unchecked")
    private List<NotificationOutbox> savedRows() {
        ArgumentCaptor<List<NotificationOutbox>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        return captor.getValue();
    }

    /** A due row whose claim succeeds; {@code attempts} is the count after the claim's increment. */
    private NotificationOutbox claimable(String id, int attempts) {
        NotificationOutbox row = row(id, attempts);
        given(repository.findDueIds(any(), any())).willReturn(List.of(id));
        given(repository.claim(eq(id), any(), any())).willReturn(1);
        given(repository.findById(id)).willReturn(Optional.of(row));
        return row;
    }

    private static NotificationOutbox row(String id, int attempts) {
        return NotificationOutbox.builder().id(id).kind(NotificationKind.LOW_BALANCE).recipient("owner@agency.test")
                .subject("Wallet low").bodyHtml("<p>Top up</p>").bodyText("Top up").status(NotificationOutboxStatus.SENDING)
                .attempts(attempts).nextAttemptAt(Instant.now().plus(NotificationOutboxService.LEASE)).createdAt(Instant.now()).build();
    }

    /** Spring's real transaction-synchronization lifecycle (commit, rollback, REQUIRES_NEW suspend/resume) without a database. */
    static class FakeTransactionManager extends AbstractPlatformTransactionManager {
        private final ThreadLocal<Boolean> active = ThreadLocal.withInitial(() -> false);

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return active.get();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            active.set(true);
        }

        @Override
        protected Object doSuspend(Object transaction) {
            active.set(false);
            return Boolean.TRUE;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            active.set(true);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            active.set(false);
        }
    }
}
