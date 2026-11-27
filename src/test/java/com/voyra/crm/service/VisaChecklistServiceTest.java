package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.VisaField;
import com.voyra.crm.enums.VisaFieldScope;
import com.voyra.crm.enums.VisaFieldType;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.VisaFieldRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VisaChecklistServiceTest {

    @Mock private VisaFieldRepository fieldRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private AgencyLogoLoader agencyLogoLoader;
    @InjectMocks private VisaChecklistService service;

    private static VisaField field(String id, String label, VisaFieldType type) {
        return VisaField.builder().id(id).scope(VisaFieldScope.COUNTRY).country("United Arab Emirates").label(label)
                .type(type).options(List.of()).mandatory(true).active(true).build();
    }

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("T1");
        // 20:00 UTC on the 26th is already the 27th in India
        service.clock = Clock.fixed(Instant.parse("2026-11-26T20:00:00Z"), ZoneOffset.UTC);
        given(fieldRepository.findActiveCountries()).willReturn(List.of("Kenya", "United Arab Emirates"));
        given(fieldRepository.findActiveFor("United Arab Emirates")).willReturn(List.of(
                field("F1", "Passport scan", VisaFieldType.FILE), field("F2", "MEDICAL", VisaFieldType.DROPDOWN)));
        given(tenantRepository.findById("T1")).willReturn(Optional.of(Tenant.builder().id("T1").agencyName("Sky Tours").build()));
        given(agencyLogoLoader.load(any())).willReturn(null);
    }

    private static String text(byte[] pdf) {
        try {
            return new com.lowagie.text.pdf.parser.PdfTextExtractor(new com.lowagie.text.pdf.PdfReader(pdf)).getTextFromPage(1);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theCountryIsMatchedIgnoringCaseAndSkippedFieldsAreLeftOff() {
        VisaChecklistService.ChecklistPdf pdf = service.pdf("  united arab emirates ", List.of("F2", "unknown"));

        assertThat(pdf.fileName()).isEqualTo("visa-checklist-united-arab-emirates.pdf");
        String text = text(pdf.bytes());
        assertThat(text).contains("Sky Tours", "United Arab Emirates visa", "Original passport", "Recent passport-size photographs",
                "PAN card copy", "Passport scan", "Prepared 27 Nov 2026").doesNotContain("MEDICAL");
    }

    @Test
    void nothingSkippedKeepsEveryField() {
        assertThat(text(service.pdf("United Arab Emirates", null).bytes()))
                .contains("Original passport", "Passport scan", "MEDICAL");
    }

    @Test
    void aCountryWithoutItsOwnFieldsIsNotFound() {
        assertThatThrownBy(() -> service.pdf("France", null)).isInstanceOf(NotFoundException.class)
                .hasMessage("No visa fields are set up for this country");
        assertThatThrownBy(() -> service.pdf(null, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void skippingEverythingIsRefused() {
        assertThat(text(service.pdf("United Arab Emirates", List.of("basic:passport", "basic:pan")).bytes()))
                .doesNotContain("Original passport").doesNotContain("PAN card copy").contains("Recent passport-size photographs");
        List<String> all = new java.util.ArrayList<>(List.of("F1", "F2"));
        VisaChecklistService.BASICS.forEach(b -> all.add(b.id()));
        assertThatThrownBy(() -> service.pdf("United Arab Emirates", all))
                .isInstanceOf(FieldValidationException.class).hasMessage("Keep at least one item on the checklist");
    }
}
