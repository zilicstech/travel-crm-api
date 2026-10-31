package com.voyra.crm.service;

import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.repository.DocumentNumberSequenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentNumberServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private DocumentNumberSequenceRepository documentNumberSequenceRepository;
    @InjectMocks private DocumentNumberService service;

    private static final LocalDate MAY_2026 = LocalDate.of(2026, 5, 10);

    /** The UPDATE ... RETURNING answers with these counter values in turn (null = no row yet), read through the real row mapper. */
    @SuppressWarnings("unchecked")
    private void counterReturns(Long... values) {
        var stub = when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any()));
        for (Long v : values) {
            stub = stub.thenAnswer(i -> {
                if (v == null) return List.of();
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong(1)).thenReturn(v);
                return List.of(i.<RowMapper<Long>>getArgument(1).mapRow(rs, 0));
            });
        }
    }

    @Test
    void anExistingSeriesHandsOutItsNextNumberWithoutSeeding() {
        counterReturns(1323L);

        assertThat(service.next(DocumentKind.AIR_INTERNATIONAL_INVOICE, MAY_2026)).isEqualTo("ITI00001323");

        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), any(), eq("AIR_INTERNATIONAL_INVOICE"), eq("ALL"));
        org.mockito.Mockito.verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void aNewYearlySeriesIsSeededThenNumberedFromOne() {
        counterReturns(null, 1L);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        assertThat(service.next(DocumentKind.RECEIPT, MAY_2026)).isEqualTo("RCP/2026-27/0001");

        verify(jdbcTemplate).update(anyString(), any(), eq("RECEIPT"), eq("2026-27"), eq("RCP"), eq(4), any());
        verify(jdbcTemplate, times(2)).query(anyString(), any(RowMapper.class), any(), eq("RECEIPT"), eq("2026-27"));
    }

    @Test
    void aSeriesSeededByAConcurrentCallerIsStillNumbered() {
        counterReturns(null, 7L);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThat(service.next(DocumentKind.CREDIT_NOTE, MAY_2026)).isEqualTo("CN00000007");
        verify(jdbcTemplate).update(anyString(), any(), eq("CREDIT_NOTE"), eq("ALL"), eq("CN"), eq(8), any());
    }

    @Test
    void aSeriesThatCannotBeFoundAfterSeedingFails() {
        counterReturns(null, null);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.next(DocumentKind.JOURNAL, LocalDate.of(2026, 2, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to allocate a document number for JOURNAL/2025-26");
    }

    @Test
    void everyKindHasItsOwnPrefix() {
        Map<DocumentKind, String> expected = Map.ofEntries(
                Map.entry(DocumentKind.TAX_INVOICE, "INV00000001"), Map.entry(DocumentKind.PROFORMA, "PI/2026-27/0001"),
                Map.entry(DocumentKind.RECEIPT, "RCP/2026-27/0001"), Map.entry(DocumentKind.CREDIT_NOTE, "CN00000001"),
                Map.entry(DocumentKind.PAYMENT_VOUCHER, "PV/2026-27/0001"), Map.entry(DocumentKind.JOURNAL, "JNV/2026-27/0001"),
                Map.entry(DocumentKind.AIR_INTERNATIONAL_INVOICE, "ITI00000001"), Map.entry(DocumentKind.AIR_DOMESTIC_INVOICE, "DTI00000001"),
                Map.entry(DocumentKind.HOTEL_INVOICE, "HTL00000001"), Map.entry(DocumentKind.RAIL_INVOICE, "RLI00000001"),
                Map.entry(DocumentKind.TRANSPORT_INVOICE, "TRN00000001"), Map.entry(DocumentKind.VISA_INVOICE, "VSA00000001"),
                Map.entry(DocumentKind.PACKAGE_INVOICE, "PKG00000001"), Map.entry(DocumentKind.MISCELLANEOUS_INVOICE, "MSC00000001"));
        assertThat(expected).hasSize(DocumentKind.values().length);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any())).thenReturn(List.of(1L));

        for (DocumentKind kind : DocumentKind.values()) {
            assertThat(service.next(kind, MAY_2026)).as(kind.name()).isEqualTo(expected.get(kind));
        }
    }
}
