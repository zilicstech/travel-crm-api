package com.voyra.crm.integration;

import com.voyra.crm.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Public V4 on a fresh database: the cancellation request tables, their status check, and the database's own guarantee
 * of one active request per booking (the service checks first; this holds when two requests race).
 */
class CancelRequestIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String booking() {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO public.platform_booking (id, tenant_id, product, status, environment) VALUES (?, 'T1', 'FLIGHT', 'TICKETED', 'MOCK')", id);
        return id;
    }

    private void request(String bookingId, String status) {
        jdbc.update("INSERT INTO public.platform_cancel_request (id, platform_booking_id, tenant_id, product, status, reason, requested_by, "
                + "requested_by_name) VALUES (?, ?, 'T1', 'FLIGHT', ?, 'TRAVEL_PLAN_CANCELLED', 'U1', 'Asha')", UUID.randomUUID().toString(), bookingId, status);
    }

    @Test
    void oneActiveRequestPerBookingWhileFinishedOnesMayPileUp() {
        String b = booking();
        request(b, "COMPLETED");
        request(b, "WITHDRAWN");
        request(b, "OPEN");
        assertThatThrownBy(() -> request(b, "IN_PROGRESS")).isInstanceOf(DataIntegrityViolationException.class);
        request(booking(), "COMPLETING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.platform_cancel_request WHERE platform_booking_id = ?", Integer.class, b)).isEqualTo(3);
    }

    @Test
    void anUnknownStatusIsRefusedAndAFileBelongsToARequest() {
        String b = booking();
        assertThatThrownBy(() -> request(b, "LOST")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO public.platform_cancel_request_file (id, request_id, file_name, content_type, size_bytes) "
                + "VALUES ('F1', 'missing', 'a.pdf', 'application/pdf', 1)")).isInstanceOf(DataIntegrityViolationException.class);
    }
}
