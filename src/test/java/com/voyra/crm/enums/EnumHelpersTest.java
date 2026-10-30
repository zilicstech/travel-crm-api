package com.voyra.crm.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnumHelpersTest {

    @Test
    void auditAndCommunicationValuesAreTheOnesStoredInTheDatabase() {
        assertThat(AuditAction.values()).extracting(Enum::name).containsExactly("CREATE", "UPDATE", "DELETE", "REVEAL");
        assertThat(CommunicationChannel.values()).extracting(Enum::name)
                .containsExactly("CALL", "WHATSAPP", "EMAIL", "SMS", "MEETING", "OTHER");
        assertThat(CommunicationDirection.values()).extracting(Enum::name).containsExactly("INBOUND", "OUTBOUND");
    }

    @Test
    void everyInvoiceCategoryNumbersOffItsOwnSeries() {
        assertThat(DocumentKind.forInvoiceCategory(InvoiceServiceCategory.AIR_INTERNATIONAL)).isEqualTo(DocumentKind.AIR_INTERNATIONAL_INVOICE);
        assertThat(DocumentKind.forInvoiceCategory(InvoiceServiceCategory.AIR_DOMESTIC)).isEqualTo(DocumentKind.AIR_DOMESTIC_INVOICE);
        assertThat(DocumentKind.forInvoiceCategory(InvoiceServiceCategory.HOTEL)).isEqualTo(DocumentKind.HOTEL_INVOICE);
        assertThat(DocumentKind.forInvoiceCategory(InvoiceServiceCategory.RAIL)).isEqualTo(DocumentKind.RAIL_INVOICE);
        assertThat(DocumentKind.forInvoiceCategory(InvoiceServiceCategory.TRANSPORT)).isEqualTo(DocumentKind.TRANSPORT_INVOICE);
        assertThat(DocumentKind.forInvoiceCategory(InvoiceServiceCategory.VISA)).isEqualTo(DocumentKind.VISA_INVOICE);
        assertThat(DocumentKind.forInvoiceCategory(InvoiceServiceCategory.PACKAGE)).isEqualTo(DocumentKind.PACKAGE_INVOICE);
        assertThat(DocumentKind.forInvoiceCategory(InvoiceServiceCategory.MISCELLANEOUS)).isEqualTo(DocumentKind.MISCELLANEOUS_INVOICE);
    }

    @Test
    void sectorWiseFollowsTheCategory() {
        assertThat(InvoiceServiceCategory.AIR_DOMESTIC.isSectorWise()).isTrue();
        assertThat(InvoiceServiceCategory.MISCELLANEOUS.isSectorWise()).isFalse();
    }

    @Test
    void anAccountCodeForNoCategoryIsRefused() {
        assertThatThrownBy(() -> SystemAccount.salesCode(null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown InvoiceServiceCategory: null");
    }

    @Test
    void tokenRolesParseLenientlyAndFallBackToTheLeastPrivilegedRole() {
        assertThat(UserType.fromString("role_accountant")).isEqualTo(UserType.ACCOUNTANT);
        assertThat(UserType.fromString("AGENCY_OWNER")).isEqualTo(UserType.AGENCY_OWNER);
        assertThat(UserType.fromString(null)).isEqualTo(UserType.AGENT);
        assertThat(UserType.fromString(" ")).isEqualTo(UserType.AGENT);
        assertThat(UserType.fromString("ROOT")).isEqualTo(UserType.AGENT);
    }
}
