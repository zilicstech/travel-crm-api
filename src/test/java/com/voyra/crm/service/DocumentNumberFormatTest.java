package com.voyra.crm.service;

import com.voyra.crm.enums.DocumentKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentNumberFormatTest {

    @Test
    void invoicesAndCreditNotesRunOnAsThePrefixAndEightDigits() {
        assertThat(DocumentNumberService.runsAcrossYears(DocumentKind.AIR_INTERNATIONAL_INVOICE)).isTrue();
        assertThat(DocumentNumberService.runsAcrossYears(DocumentKind.CREDIT_NOTE)).isTrue();
        assertThat(DocumentNumberService.format("ITI", DocumentNumberService.ALL_YEARS, 1)).isEqualTo("ITI00000001");
        assertThat(DocumentNumberService.format("ITI", DocumentNumberService.ALL_YEARS, 1323)).isEqualTo("ITI00001323");
    }

    @Test
    void receiptsJournalsPaymentsAndProformasKeepTheirYearlySeries() {
        for (DocumentKind k : new DocumentKind[]{DocumentKind.RECEIPT, DocumentKind.JOURNAL, DocumentKind.PAYMENT_VOUCHER, DocumentKind.PROFORMA}) {
            assertThat(DocumentNumberService.runsAcrossYears(k)).as(k.name()).isFalse();
        }
        assertThat(DocumentNumberService.format("RCP", "2026-27", 7)).isEqualTo("RCP/2026-27/0007");
    }
}
