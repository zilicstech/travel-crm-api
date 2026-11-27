package com.voyra.crm.service;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.entity.VisaField;
import com.voyra.crm.enums.VisaFieldType;
import com.voyra.crm.exception.FieldValidationException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.repository.VisaFieldRepository;
import com.voyra.crm.util.FinancialYear;
import com.voyra.crm.util.VisaChecklistPdfRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The client checklist of one country's visa: the documents every visa needs ({@link #BASICS}), then the agency's active
 * common fields and that country's, less the ones the agent left out (internal tracking fields a client should not see), printed by {@link VisaChecklistPdfRenderer}.
 */
@Service
@RequiredArgsConstructor
public class VisaChecklistService {

    private final VisaFieldRepository fieldRepository;
    private final TenantRepository tenantRepository;
    private final AgencyLogoLoader agencyLogoLoader;
    Clock clock = Clock.systemUTC();

    public record ChecklistPdf(String fileName, byte[] bytes) {
    }

    /** A document every visa asks for, printed before the agency's own fields; {@code id} is what skip names. */
    record Basic(String id, String label, String note, boolean required) {
    }

    // the frontend lists the same ids in VisaChecklistDownloadModal (BASICS)
    static final List<Basic> BASICS = List.of(
            new Basic("basic:passport", "Original passport",
                    "Valid for at least 6 months after your return date, with at least 2 blank pages", true),
            new Basic("basic:passport-copy", "Copy of the passport's first and last pages", null, true),
            new Basic("basic:photo", "Recent passport-size photographs", "White background, taken in the last 6 months", true),
            new Basic("basic:pan", "PAN card copy", null, false));

    /** @param skip field ids to leave off; unknown ids are ignored */
    @Transactional(readOnly = true)
    public ChecklistPdf pdf(String country, Collection<String> skip) {
        String wanted = country == null ? "" : country.trim();
        // the country as Settings spells it; only a country with fields of its own gets a checklist
        String name = fieldRepository.findActiveCountries().stream().filter(c -> c.equalsIgnoreCase(wanted)).findFirst()
                .orElseThrow(() -> new NotFoundException("No visa fields are set up for this country"));
        Set<String> left = skip == null ? Set.of() : Set.copyOf(skip);
        List<VisaChecklistPdfRenderer.Item> items = java.util.stream.Stream.concat(
                BASICS.stream().filter(b -> !left.contains(b.id()))
                        .map(b -> new VisaChecklistPdfRenderer.Item(b.label(), VisaFieldType.FILE, List.of(), b.required(), b.note())),
                fieldRepository.findActiveFor(name).stream().filter(f -> !left.contains(f.getId())).map(VisaChecklistService::item))
                .toList();
        if (items.isEmpty()) {
            throw new FieldValidationException("skip", "Keep at least one item on the checklist");
        }
        Tenant agency = tenantRepository.findById(TenantContext.getTenantId()).orElseThrow();
        byte[] bytes = VisaChecklistPdfRenderer.render(new VisaChecklistPdfRenderer.ChecklistData(agency.getAgencyName(),
                agency.getContactEmail(), agency.getContactPhone(), agency.getAddress(), agencyLogoLoader.load(agency),
                name, FinancialYear.dateOf(clock.instant()), items));
        return new ChecklistPdf("visa-checklist-" + name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "") + ".pdf", bytes);
    }

    private static VisaChecklistPdfRenderer.Item item(VisaField f) {
        return new VisaChecklistPdfRenderer.Item(f.getLabel(), f.getType(), f.getOptions(), f.isMandatory());
    }
}
