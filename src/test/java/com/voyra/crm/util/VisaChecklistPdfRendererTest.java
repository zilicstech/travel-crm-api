package com.voyra.crm.util;

import com.voyra.crm.enums.VisaFieldType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The client checklist of one country's visa, read back from the rendered PDF. */
class VisaChecklistPdfRendererTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 27);

    @Test
    void documentsAndDetailsGoInTheirOwnSectionsWithWhatEachNeeds() {
        byte[] pdf = VisaChecklistPdfRenderer.render(new VisaChecklistPdfRenderer.ChecklistData("Sky Tours", "hi@sky.test",
                "+91 98765 43210", "MG Road, Pune", null, "Kenya", DAY, List.of(
                new VisaChecklistPdfRenderer.Item("Original passport", VisaFieldType.FILE, List.of(), true, "Valid for 6 months"),
                new VisaChecklistPdfRenderer.Item("Passport scan", VisaFieldType.FILE, List.of(), true),
                new VisaChecklistPdfRenderer.Item("Hotel booking", VisaFieldType.FILE, null, false),
                new VisaChecklistPdfRenderer.Item("Date of travel", VisaFieldType.DATE, List.of(), true),
                new VisaChecklistPdfRenderer.Item("Travelled before", VisaFieldType.YES_NO, List.of(), false),
                new VisaChecklistPdfRenderer.Item("Purpose", VisaFieldType.DROPDOWN, List.of("Tourism", "Business"), true),
                new VisaChecklistPdfRenderer.Item("Father's name", VisaFieldType.TEXT, List.of(), false))));

        String t = PdfText.flat(pdf);
        assertThat(t).contains(PdfText.flat("Sky Tours", "MG Road, Pune · hi@sky.test · +91 98765 43210", "VISA CHECKLIST",
                "Prepared 27 Nov 2026", "Kenya visa", "Documents to provide", "3 items", "Original passport", "Valid for 6 months", "Passport scan", "Required",
                "Hotel booking", "Optional", "Details we need from you", "4 items", "Date of travel", "A date",
                "Travelled before", "Yes or No", "Purpose", "One of: Tourism, Business", "Father's name",
                "Questions? Contact Sky Tours at +91 98765 43210 or hi@sky.test.", "Prepared with", "Page 1"));
    }

    @Test
    void aChecklistWithOnlyDetailsAndNoAgencyContactStillReads() {
        byte[] junkLogo = {1, 2, 3};
        String t = PdfText.flat(VisaChecklistPdfRenderer.render(new VisaChecklistPdfRenderer.ChecklistData(null, " ", null,
                null, junkLogo, "Japan", DAY, List.of(new VisaChecklistPdfRenderer.Item("Occupation", VisaFieldType.TEXT, null, true)))));

        assertThat(t).contains(PdfText.flat("Your travel agency", "Details we need from you", "1 item", "Occupation",
                "Questions? Contact Your travel agency."));
        assertThat(t).doesNotContain(PdfText.flat("Documents to provide"));
    }

    @Test
    void anAgencyLogoIsPrintedAndADocumentOnlyListHasNoDetailsSection() throws Exception {
        byte[] logo;
        try (var in = getClass().getResourceAsStream("/branding/voyra-wordmark.png")) {
            logo = in.readAllBytes();
        }
        String t = PdfText.flat(VisaChecklistPdfRenderer.render(new VisaChecklistPdfRenderer.ChecklistData("Sky Tours", null,
                "+91 1", null, logo, "Kenya", DAY, List.of(new VisaChecklistPdfRenderer.Item("Photo", VisaFieldType.FILE, null, true)))));

        assertThat(t).contains(PdfText.flat("Sky Tours", "Documents to provide", "Photo", "Contact Sky Tours at +91 1."));
        assertThat(t).doesNotContain(PdfText.flat("Details we need"));
    }

    @Test
    void aBlankAgencyNameReadsAsYourTravelAgency() {
        assertThat(PdfText.flat(VisaChecklistPdfRenderer.render(new VisaChecklistPdfRenderer.ChecklistData(" ", null, null, null,
                null, "Japan", DAY, List.of(new VisaChecklistPdfRenderer.Item("Photo", VisaFieldType.FILE, null, true))))))
                .contains(PdfText.flat("Questions? Contact Your travel agency."));
    }

    @Test
    void aDropdownWithoutOptionsGetsNoHint() {
        assertThat(VisaChecklistPdfRenderer.hint(new VisaChecklistPdfRenderer.Item("x", VisaFieldType.DROPDOWN, null, true))).isNull();
        assertThat(VisaChecklistPdfRenderer.hint(new VisaChecklistPdfRenderer.Item("x", VisaFieldType.DROPDOWN, List.of(), true))).isNull();
    }
}
