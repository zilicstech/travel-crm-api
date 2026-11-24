package com.voyra.crm.service;

import com.voyra.crm.entity.Visa;
import com.voyra.crm.entity.VisaField;
import com.voyra.crm.entity.VisaFile;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.enums.VisaFieldType;
import com.voyra.crm.models.VisaListFilter;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.util.CsvWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** The visa sheet as CSV, with the same filters, order and scope as the list; audited, because it carries passport numbers. */
@Service
@RequiredArgsConstructor
@Slf4j
public class VisaExportService {

    static final int MAX_ROWS = 5000;
    private static final DateTimeFormatter OPENED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final VisaService visaService;
    private final VisaRepository visaRepository;
    private final AuditService auditService;

    public record Export(String csv, int rows, boolean truncated) {
    }

    @Transactional
    public Export export(VisaListFilter filter, String sort) {
        VisaService.VisaQuery q = visaService.query(filter, sort);
        List<Visa> found = visaRepository.findAll(q.spec(), PageRequest.of(0, MAX_ROWS + 1, q.sort())).getContent();
        boolean truncated = found.size() > MAX_ROWS;
        List<Visa> rows = truncated ? found.subList(0, MAX_ROWS) : found;
        Map<String, List<VisaFile>> files = visaService.filesOf(rows);

        List<String> header = new ArrayList<>(List.of("No.", "Opened", "Client", "Traveller", "Passport no.", "Passport expiry",
                "Nationality", "Agent", "Status", "Country", "Visa type"));
        q.fields().forEach(f -> header.add(f.getLabel()));
        header.addAll(List.of("Purchase", "Purchase currency", "Selling", "Supplier", "Remarks"));

        List<List<String>> lines = new ArrayList<>();
        for (Visa v : rows) {
            Map<String, String> fileNames = files.getOrDefault(v.getId(), List.of()).stream()
                    .collect(Collectors.toMap(VisaFile::getFieldId, VisaFile::getFileName));
            List<String> line = new ArrayList<>(List.of(
                    String.valueOf(v.getCaseNo()), v.getCreatedDate() == null ? "" : OPENED.format(v.getCreatedDate()),
                    text(v.getClientName()), text(v.getMemberName()), text(v.getPassportNumber()), text(v.getPassportExpiry()),
                    text(v.getNationality()), text(v.getAgentName()), status(v), text(v.getCountry()), text(v.getVisaType())));
            for (VisaField f : q.fields()) {
                line.add(f.getType() == VisaFieldType.FILE ? text(fileNames.get(f.getId())) : cell(f, v.getFieldValues().get(f.getId())));
            }
            line.addAll(List.of(money(v.getPurchasePrice()), text(v.getPurchaseCurrency()), money(v.getSellingPrice()),
                    text(v.getVendorName()), text(v.getRemarks())));
            lines.add(line.stream().map(VisaExportService::safe).toList());
        }
        auditService.recordReveal(AuditEntityType.VISA, "export", "Visa sheet exported: " + rows.size() + " rows" + describe(filter));
        log.info("Visa sheet exported: rows={}, truncated={}", rows.size(), truncated);
        return new Export(CsvWriter.write(header.stream().map(VisaExportService::safe).toList(), lines), rows.size(), truncated);
    }

    /** A cell Excel would run as a formula (=, +, -, @) is written as text. */
    static String safe(String value) {
        return !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }

    private static String cell(VisaField field, String value) {
        if (value == null) return "";
        return field.getType() == VisaFieldType.YES_NO ? (value.equals("true") ? "Yes" : "No") : value;
    }

    private static String status(Visa v) {
        String name = v.getStatus().name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "" : amount.toPlainString();
    }

    private static String text(Object value) {
        return Objects.toString(value, "");
    }

    private static String describe(VisaListFilter f) {
        Map<String, Object> parts = new java.util.LinkedHashMap<>();
        parts.put("country", f.country());
        parts.put("status", f.status());
        parts.put("agent", f.agentId());
        parts.put("from", f.openedFrom());
        parts.put("to", f.openedTo());
        parts.put("search", f.q() == null || f.q().isBlank() ? null : "yes");
        parts.put("field filters", f.fieldFilters().isEmpty() ? null : f.fieldFilters().size());
        String text = parts.entrySet().stream().filter(e -> e.getValue() != null && !e.getValue().toString().isBlank())
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(", "));
        return text.isEmpty() ? "" : " (" + text + ")";
    }
}
