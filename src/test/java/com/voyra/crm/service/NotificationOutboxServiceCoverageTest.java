package com.voyra.crm.service;

import com.voyra.crm.entity.NotificationOutbox;
import com.voyra.crm.enums.NotificationKind;
import com.voyra.crm.enums.NotificationOutboxStatus;
import com.voyra.crm.repository.NotificationOutboxRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationOutboxServiceCoverageTest {

    @Mock private NotificationOutboxRepository repository;
    @Mock private JavaMailSender mailSender;

    private NotificationOutboxService outbox;

    @BeforeEach
    void setUp() {
        outbox = new NotificationOutboxService(repository, mailSender, new FakeTransactionManager(), " Voyra <no-reply@voyra.test> ", 5);
    }

    private NotificationOutbox claim(String id, int attempts) {
        NotificationOutbox row = NotificationOutbox.builder().id(id).kind(NotificationKind.LOW_BALANCE).recipient("owner@agency.test")
                .subject("Wallet low").bodyHtml("<p>x</p>").bodyText("x").status(NotificationOutboxStatus.SENDING).attempts(attempts).build();
        when(repository.findDueIds(any(), any())).thenReturn(List.of(id));
        when(repository.claim(eq(id), any(), any())).thenReturn(1);
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        return row;
    }

    @Test
    void aRowDeletedWhileItWasSendingIsLeftAlone() {
        NotificationOutbox row = claim("N1", 1);
        when(repository.findById("N1")).thenReturn(Optional.of(row), Optional.empty());

        outbox.drainDue();

        verify(mailSender).send(any(MimeMessage.class));
        verify(repository, never()).save(any());
        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.SENDING);
    }

    @Test
    void aLongSendErrorIsCutTo500Characters() {
        NotificationOutbox row = claim("N1", 1);
        when(repository.findById("N1")).thenReturn(Optional.of(row));
        doThrow(new MailSendException("x".repeat(800))).when(mailSender).send(any(MimeMessage.class));

        outbox.drainDue();

        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(row.getLastError()).hasSize(500).startsWith("MailSendException: xxx");
    }

    @Test
    void aSubjectIsCutTo300CharactersAndAMissingOneStaysMissing() {
        outbox.enqueue(NotificationKind.PLATFORM_ALERT, () -> new NotificationOutboxService.Outgoing(List.of("a@voyra.test"),
                new NotificationTemplates.Email("s".repeat(400), "<p>x</p>", "x")));
        outbox.enqueue(NotificationKind.PLATFORM_ALERT, () -> new NotificationOutboxService.Outgoing(List.of("b@voyra.test"),
                new NotificationTemplates.Email(null, "<p>x</p>", "x")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NotificationOutbox>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository, org.mockito.Mockito.times(2)).saveAll(saved.capture());
        assertThat(saved.getAllValues().get(0).get(0).getSubject()).hasSize(300);
        assertThat(saved.getAllValues().get(1).get(0).getSubject()).isNull();
    }
}
