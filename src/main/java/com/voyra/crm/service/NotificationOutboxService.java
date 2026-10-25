package com.voyra.crm.service;

import com.voyra.crm.config.SmtpConfigured;
import com.voyra.crm.entity.NotificationOutbox;
import com.voyra.crm.enums.NotificationKind;
import com.voyra.crm.enums.NotificationOutboxStatus;
import com.voyra.crm.repository.NotificationOutboxRepository;
import com.voyra.crm.util.UniqueIdResolver;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The email outbox. {@link #enqueue} writes rows only after the caller's transaction commits (a rollback queues nothing);
 * recipients are resolved then too. {@link #drainDue} sends them: each row is claimed by a conditional update in its own transaction (so two instances never
 * both send it), sent outside any transaction, then marked SENT, retried with exponential backoff, or FAILED after
 * {@code maxAttempts}. A claim leases the row for {@link #LEASE}, so a crash mid-send is retried once the lease runs out.
 */
@Slf4j
@Service
@Conditional(SmtpConfigured.class)
public class NotificationOutboxService {

    static final Duration LEASE = Duration.ofMinutes(10);
    static final Duration BASE_BACKOFF = Duration.ofMinutes(1);
    static final int BATCH = 50;

    private final NotificationOutboxRepository repository;
    private final JavaMailSender mailSender;
    private final TransactionTemplate tx;
    private final String from;
    private final int maxAttempts;

    public NotificationOutboxService(NotificationOutboxRepository repository, JavaMailSender mailSender,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${app.mail.from:}") String from,
                                     @Value("${app.notifications.outbox.max-attempts:5}") int maxAttempts) {
        if (!StringUtils.hasText(from)) {
            throw new IllegalStateException("SMTP_HOST is set but MAIL_FROM is not - set MAIL_FROM (e.g. Voyra <no-reply@your-domain>)");
        }
        this.repository = repository;
        this.mailSender = mailSender;
        // REQUIRES_NEW: enqueue runs in afterCommit, where joining the finished transaction would never commit
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.from = from.trim();
        this.maxAttempts = maxAttempts;
    }

    /** Who gets it and what it says. */
    public record Outgoing(Collection<String> recipients, NotificationTemplates.Email email) {
    }

    /**
     * Queues one row per distinct recipient after the current transaction commits (at once when there is none). {@code build}
     * (recipient lookup + rendering) runs then too, in its own transaction: a failing lookup can never mark the caller's
     * transaction rollback-only, and a rolled-back caller never even looks anyone up. Never throws; null means no recipient.
     */
    public void enqueue(NotificationKind kind, Supplier<Outgoing> build) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(kind, build);
                }
            });
        } else {
            write(kind, build);
        }
    }

    private void write(NotificationKind kind, Supplier<Outgoing> build) {
        try {
            tx.executeWithoutResult(s -> {
                Outgoing out = build.get();
                List<NotificationOutbox> rows = out == null ? List.of() : rows(kind, out);
                if (rows.isEmpty()) {
                    log.warn("No recipient for a {} notification - nothing queued", kind);
                    return;
                }
                rows.forEach(r -> r.setId(UniqueIdResolver.resolve(repository::existsById)));
                repository.saveAll(rows);
            });
        } catch (RuntimeException e) {
            // the business transaction already committed: losing a notice is logged, never thrown at the caller
            log.error("{} notification not queued: {}", kind, e.getClass().getSimpleName());
        }
    }

    private static List<NotificationOutbox> rows(NotificationKind kind, Outgoing out) {
        Instant now = Instant.now();
        NotificationTemplates.Email email = out.email();
        return out.recipients().stream().filter(StringUtils::hasText).map(String::trim).distinct()
                .map(to -> NotificationOutbox.builder().kind(kind).recipient(to).subject(truncate(email.subject(), 300))
                        .bodyHtml(email.html()).bodyText(email.text()).status(NotificationOutboxStatus.PENDING)
                        .attempts(0).nextAttemptAt(now).createdAt(now).build())
                .toList();
    }

    public void drainDue() {
        List<String> due = repository.findDueIds(Instant.now(), PageRequest.of(0, BATCH));
        for (String id : due) {
            try {
                sendOne(id);
            } catch (RuntimeException e) {
                log.error("Notification {} could not be processed: {}", id, e.getClass().getSimpleName());
            }
        }
    }

    private void sendOne(String id) {
        Instant now = Instant.now();
        NotificationOutbox row = tx.execute(s -> repository.claim(id, now, now.plus(LEASE)) == 1
                ? repository.findById(id).orElse(null) : null);
        if (row == null) {
            return; // another instance claimed it first
        }
        String error = null;
        try {
            mailSender.send(message(row));
        } catch (Exception e) {
            // class + message only: never the body; the message may name the recipient, so it goes to the row, not the log
            error = e.getClass().getSimpleName() + ": " + Objects.toString(e.getMessage(), "");
        }
        String failure = error;
        tx.executeWithoutResult(s -> finish(id, failure));
    }

    private MimeMessage message(NotificationOutbox row) throws jakarta.mail.MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        helper.setTo(row.getRecipient());
        helper.setSubject(row.getSubject());
        helper.setText(Objects.toString(row.getBodyText(), ""), Objects.toString(row.getBodyHtml(), ""));
        return message;
    }

    private void finish(String id, String error) {
        NotificationOutbox row = repository.findById(id).orElse(null);
        if (row == null) {
            return;
        }
        Instant now = Instant.now();
        if (error == null) {
            row.setStatus(NotificationOutboxStatus.SENT);
            row.setSentAt(now);
            row.setLastError(null);
            // a sent email's body is not kept: it can carry a resume link or personal details
            row.setBodyHtml(null);
            row.setBodyText(null);
        } else if (row.getAttempts() >= maxAttempts) {
            row.setStatus(NotificationOutboxStatus.FAILED);
            row.setLastError(truncate(error, 500));
            // dead row: subject, recipient and error stay for the admin; the body (resume token, personal details) goes
            row.setBodyHtml(null);
            row.setBodyText(null);
            log.error("Notification {} ({}) failed after {} attempts: {}", id, row.getKind(), row.getAttempts(), error.split(":", 2)[0]);
        } else {
            row.setStatus(NotificationOutboxStatus.PENDING);
            row.setNextAttemptAt(nextAttempt(now, row.getAttempts()));
            row.setLastError(truncate(error, 500));
            log.warn("Notification {} ({}) attempt {} failed, retrying: {}", id, row.getKind(), row.getAttempts(), error.split(":", 2)[0]);
        }
        repository.save(row);
    }

    /** 1, 2, 4, 8 ... minutes after the given attempt failed. */
    static Instant nextAttempt(Instant now, int attempts) {
        return now.plus(BASE_BACKOFF.multipliedBy(1L << Math.min(Math.max(attempts - 1, 0), 10)));
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
