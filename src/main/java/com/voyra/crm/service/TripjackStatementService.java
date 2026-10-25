package com.voyra.crm.service;

import com.voyra.crm.dto.StatementImportResponse;
import com.voyra.crm.dto.StatementLineResponse;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformStatementImport;
import com.voyra.crm.entity.PlatformStatementLine;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.StatementLineStatus;
import com.voyra.crm.enums.StatementLineType;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.VoyraEarnedSource;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.PlatformStatementImportRepository;
import com.voyra.crm.repository.PlatformStatementLineRepository;
import com.voyra.crm.service.booking.PlatformBookingEventRecorder;
import com.voyra.crm.util.PricingMath;
import com.voyra.crm.util.UniqueIdResolver;
import com.voyra.crm.util.UploadValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Imports a Tripjack statement (spec 5.1): a SALE line records what Voyra actually earned
 * ({@code voyra_earned_actual}, source STATEMENT - the estimate in {@code voyra_earned} is kept), a REFUND line
 * records the actual clawback as the reversal, and voyra_net is re-derived on the earning in force.
 * A (booking, type) line applies once, however often it is imported: the same file may be uploaded again after an
 * unmatched cause is fixed, and its applied lines then show as DUPLICATE. The file's SHA-256 is recorded on the import.
 * A file with any unreadable line is refused whole, so nothing is half-imported. Only bookings of the
 * import's supplier environment match, so a live statement never touches test bookings.
 */
@Service
@RequiredArgsConstructor
public class TripjackStatementService {

    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MM-uuuu");

    private final PlatformBookingRepository bookingRepository;
    private final PlatformStatementImportRepository importRepository;
    private final PlatformStatementLineRepository lineRepository;
    private final PlatformAuditService auditService;
    private final PlatformBookingEventRecorder events;
    private final TripjackStatementConfig config;
    private final NotificationService notificationService;

    private record Parsed(int lineNo, String bookingId, String pnr, StatementLineType type, BigDecimal amount,
                          BigDecimal commission, LocalDate date) {
    }

    @Transactional
    public StatementImportResponse importStatement(MultipartFile file, SupplierEnvironment environment, String userId, String userName) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new IllegalArgumentException("Upload the statement as a CSV file");
        }
        UploadValidator.assertAllowed(file, MAX_BYTES);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("The file could not be read - try uploading it again");
        }
        List<Parsed> parsed = parse(bytes);
        // the same file may come again (after fixing an unmatched line): per-line idempotency keeps every line applied once
        String sha = sha256(bytes);

        String importId = UniqueIdResolver.resolve(importRepository::existsById);
        Set<String> appliedHere = new HashSet<>();
        List<String> worse = new ArrayList<>();
        List<PlatformStatementLine> lines = new ArrayList<>();
        for (Parsed p : parsed) {
            lines.add(apply(importId, environment, p, appliedHere, worse));
        }
        int applied = count(lines, StatementLineStatus.APPLIED);
        int duplicate = count(lines, StatementLineStatus.DUPLICATE);
        int unmatched = count(lines, StatementLineStatus.UNMATCHED);
        PlatformStatementImport imp = importRepository.save(PlatformStatementImport.builder()
                .id(importId).fileName(truncate(name, 255)).fileSha256(sha).totalLines(lines.size())
                .appliedLines(applied).duplicateLines(duplicate).unmatchedLines(unmatched)
                .importedBy(userId).importedByName(userName).importedAt(Instant.now()).build());
        lineRepository.saveAll(lines);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fileName", imp.getFileName());
        detail.put("sha256", sha);
        detail.put("environment", environment);
        detail.put("applied", applied);
        detail.put("duplicate", duplicate);
        detail.put("unmatched", unmatched);
        auditService.record(PlatformAuditAction.STATEMENT_IMPORT, "STATEMENT_IMPORT", importId, detail);
        if (!worse.isEmpty()) {
            notificationService.platformAlert("Statement import pushed " + worse.size() + " booking(s) further into loss",
                    "Tripjack statement " + imp.getFileName() + " (import " + importId + ") lowered Voyra net below zero on:\n"
                            + String.join("\n", worse));
        }
        return StatementImportResponse.builder().id(importId).fileName(imp.getFileName()).importedAt(imp.getImportedAt())
                .importedByName(userName).totalLines(lines.size()).appliedLines(applied).duplicateLines(duplicate)
                .unmatchedLines(unmatched).lines(lines.stream().map(TripjackStatementService::view).toList()).build();
    }

    private PlatformStatementLine apply(String importId, SupplierEnvironment environment, Parsed p, Set<String> appliedHere,
                                        List<String> worse) {
        PlatformStatementLine line = PlatformStatementLine.builder()
                .id(UniqueIdResolver.resolve(lineRepository::existsById)).importId(importId).lineNo(p.lineNo())
                .supplierBookingId(truncate(p.bookingId(), 120)).pnr(truncate(p.pnr(), 40)).type(p.type())
                .amount(p.amount()).commission(p.commission()).lineDate(p.date()).build();
        // the PNR is only a fallback for a line without a booking id: airlines recycle PNRs
        List<String> ids;
        String by;
        if (p.bookingId() != null) {
            ids = bookingRepository.findIdsBySupplierBookingId(p.bookingId(), environment);
            by = "booking id";
        } else if (p.pnr() != null) {
            ids = bookingRepository.findIdsByPnr(p.pnr(), environment);
            by = "PNR";
        } else {
            return unmatched(line, "No booking id or PNR");
        }
        if (ids.isEmpty()) {
            return unmatched(line, "No booking with this " + by);
        }
        if (ids.size() > 1) {
            return unmatched(line, (by.equals("PNR") ? "PNR" : "Booking id") + " matches " + ids.size() + " bookings");
        }
        // locked and read fresh: a cancellation committed meanwhile is what this line sees and re-derives from
        PlatformBooking b = bookingRepository.findByIdForUpdate(ids.get(0))
                .orElseThrow(() -> new IllegalStateException("Booking " + ids.get(0) + " disappeared during the import"));
        line.setPlatformBookingId(b.getId());
        if (b.getTicketedAt() == null) {
            return unmatched(line, "Booking was never ticketed (" + b.getStatus() + ")");
        }
        if (p.type() == StatementLineType.REFUND && b.getStatus() != PlatformBookingStatus.CANCELLED) {
            return unmatched(line, "Refund for a booking that is not cancelled (" + b.getStatus() + ")");
        }
        String key = b.getId() + ":" + p.type();
        if (appliedHere.contains(key)
                || lineRepository.existsByPlatformBookingIdAndTypeAndStatus(b.getId(), p.type(), StatementLineStatus.APPLIED)) {
            line.setStatus(StatementLineStatus.DUPLICATE);
            line.setReason("Already applied to this booking - not applied again");
            return line;
        }
        appliedHere.add(key);

        BigDecimal netBefore = nz(b.getVoyraNet());
        if (p.type() == StatementLineType.SALE) {
            BigDecimal actual = p.commission();
            boolean statementRefund = appliedHere.contains(b.getId() + ":" + StatementLineType.REFUND)
                    || lineRepository.existsByPlatformBookingIdAndTypeAndStatus(b.getId(), StatementLineType.REFUND, StatementLineStatus.APPLIED);
            // a cancelled fare's reversal follows what it earned, until a statement says what was actually clawed back
            BigDecimal reversal = b.getStatus() == PlatformBookingStatus.CANCELLED && !statementRefund ? actual : b.getVoyraEarnedReversal();
            rederive(b, actual, reversal);
            b.setVoyraEarnedActual(actual);
            b.setVoyraEarnedSource(VoyraEarnedSource.STATEMENT);
            if (b.getGross() != null && p.amount().compareTo(b.getGross()) != 0) {
                line.setReason("Statement amount " + p.amount() + " differs from the booked gross " + b.getGross());
            }
        } else {
            rederive(b, earnedInForce(b), p.commission().abs());
        }
        bookingRepository.save(b);
        events.record(b.getId(), "STATEMENT_" + p.type(), Map.of("importId", importId, "line", p.lineNo(),
                "commission", p.commission(), "voyraNetBefore", String.valueOf(netBefore), "voyraNet", b.getVoyraNet()));
        if (b.getVoyraNet().signum() < 0 && b.getVoyraNet().compareTo(netBefore) < 0) {
            worse.add("Booking " + b.getId() + " (agency " + b.getTenantId() + ", PNR " + b.getPnr() + "): "
                    + netBefore + " -> " + b.getVoyraNet());
        }
        line.setStatus(StatementLineStatus.APPLIED);
        return line;
    }

    /** The earning voyra_net is built on: the statement figure once imported, else the estimate stamped at review. */
    private static BigDecimal earnedInForce(PlatformBooking b) {
        return b.getVoyraEarnedActual() != null ? b.getVoyraEarnedActual() : b.getVoyraEarned();
    }

    /**
     * voyra_net re-derived with the spec 5.1 formula ({@link PricingMath#voyraNetOf}) on the new earning in force,
     * less the reversal. Adjustments made after the review stamp - a supplier fare variance, the commission
     * recovered and any refund Voyra kept on a cancellation - are not columns, so they carry over unchanged
     * as the difference between the current net and the formula on the old figures.
     */
    private static void rederive(PlatformBooking b, BigDecimal earned, BigDecimal reversal) {
        BigDecimal adjustments = nz(b.getVoyraNet()).subtract(netOf(b, earnedInForce(b), b.getVoyraEarnedReversal()));
        b.setVoyraEarnedReversal(reversal);
        b.setVoyraNet(netOf(b, earned, reversal).add(adjustments));
    }

    private static BigDecimal netOf(PlatformBooking b, BigDecimal earned, BigDecimal reversal) {
        return PricingMath.voyraNetOf(nz(earned), nz(b.getGross()), nz(b.getDisplayed()), nz(b.getAgencyCommission()))
                .subtract(nz(reversal));
    }

    private List<Parsed> parse(byte[] bytes) {
        List<CsvStatementParser.CsvRecord> rows = CsvStatementParser.records(
                CsvStatementParser.stripBom(new String(bytes, StandardCharsets.UTF_8)));
        if (rows.isEmpty() || blank(rows.get(0))) {
            throw new IllegalArgumentException("The statement has no header row");
        }
        Map<String, Integer> header = new HashMap<>();
        List<String> names = rows.get(0).fields();
        for (int i = 0; i < names.size(); i++) {
            header.put(names.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        TripjackStatementConfig.Columns c = config.getColumns();
        List<String> missing = new ArrayList<>();
        for (String col : List.of(c.getBookingId(), c.getPnr(), c.getType(), c.getAmount(), c.getCommission(), c.getDate())) {
            if (!header.containsKey(col.toLowerCase(Locale.ROOT))) {
                missing.add(col);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("The header row is missing column(s): " + String.join(", ", missing));
        }
        List<Parsed> out = new ArrayList<>();
        for (CsvStatementParser.CsvRecord row : rows.subList(1, rows.size())) {
            if (blank(row)) {
                continue;
            }
            int lineNo = row.line();
            List<String> v = row.fields();
            String typeRaw = cell(v, header, c.getType());
            StatementLineType type;
            try {
                type = StatementLineType.valueOf(typeRaw == null ? "" : typeRaw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Line " + lineNo + ": type must be SALE or REFUND, got \"" + typeRaw + "\"");
            }
            BigDecimal amount = decimal(lineNo, "amount", cell(v, header, c.getAmount()));
            BigDecimal commission = decimal(lineNo, "commission", cell(v, header, c.getCommission()));
            if (type == StatementLineType.SALE && commission.signum() < 0) {
                throw new IllegalArgumentException("Line " + lineNo + ": a SALE commission cannot be negative");
            }
            out.add(new Parsed(lineNo, cell(v, header, c.getBookingId()), cell(v, header, c.getPnr()), type, amount, commission,
                    date(lineNo, cell(v, header, c.getDate()))));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("The statement has no lines below the header");
        }
        return out;
    }

    private static boolean blank(CsvStatementParser.CsvRecord row) {
        return row.fields().stream().allMatch(String::isBlank);
    }

    /** The trimmed cell under a header, or null when blank. */
    private static String cell(List<String> values, Map<String, Integer> header, String column) {
        Integer i = header.get(column.toLowerCase(Locale.ROOT));
        if (i == null || i >= values.size()) {
            return null;
        }
        String s = values.get(i).trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal decimal(int lineNo, String field, String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Line " + lineNo + ": " + field + " is blank");
        }
        try {
            return CsvStatementParser.toDecimal(raw).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Line " + lineNo + ": " + field + " \"" + raw + "\" is not a number");
        }
    }

    private static LocalDate date(int lineNo, String raw) {
        if (raw != null) {
            for (DateTimeFormatter f : List.of(DateTimeFormatter.ISO_LOCAL_DATE, DMY)) {
                try {
                    return LocalDate.parse(raw, f);
                } catch (DateTimeParseException ignored) {
                    // try the next accepted format
                }
            }
        }
        throw new IllegalArgumentException("Line " + lineNo + ": date \"" + raw + "\" is not yyyy-MM-dd or dd-MM-yyyy");
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PlatformStatementLine unmatched(PlatformStatementLine line, String reason) {
        line.setStatus(StatementLineStatus.UNMATCHED);
        line.setReason(reason);
        return line;
    }

    private static StatementLineResponse view(PlatformStatementLine l) {
        return StatementLineResponse.builder().lineNo(l.getLineNo()).supplierBookingId(l.getSupplierBookingId()).pnr(l.getPnr())
                .type(l.getType()).amount(l.getAmount()).commission(l.getCommission()).date(l.getLineDate())
                .status(l.getStatus()).reason(l.getReason()).platformBookingId(l.getPlatformBookingId()).build();
    }

    private static int count(List<PlatformStatementLine> lines, StatementLineStatus status) {
        return (int) lines.stream().filter(l -> l.getStatus() == status).count();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
