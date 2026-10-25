package com.voyra.crm.service;

import com.voyra.crm.dto.PnlResponse;
import com.voyra.crm.dto.PnlRow;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.enums.VoyraEarnedSource;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Platform P&L (spec 9) over bookings ticketed in a date range: plain sums of the money stamped on each booking. */
@Service
@RequiredArgsConstructor
public class PlatformPnlService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Ticketed at some point: a later cancellation shows in cancellations, reversals and voyra_net, not by dropping the fare. */
    private static final Set<PlatformBookingStatus> COUNTED =
            Set.of(PlatformBookingStatus.TICKETED, PlatformBookingStatus.CANCELLING, PlatformBookingStatus.CANCELLED);

    private final PlatformBookingRepository bookingRepository;
    private final TenantRepository tenantRepository;

    @Transactional(readOnly = true)
    public PnlResponse pnl(LocalDate from, LocalDate to, PricingProduct product, String tenantId, SupplierEnvironment environment) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        if (from.plusYears(1).isBefore(to)) {
            throw new IllegalArgumentException("Pick a range of at most one year");
        }
        // shortcut: aggregated in memory over the range's rows - fine at launch volumes; move to a GROUP BY query past ~100k bookings a year
        List<PlatformBooking> rows = bookingRepository.findByTicketedAtGreaterThanEqualAndTicketedAtLessThan(
                        from.atStartOfDay(IST).toInstant(), to.plusDays(1).atStartOfDay(IST).toInstant()).stream()
                .filter(b -> COUNTED.contains(b.getStatus()))
                .filter(b -> product == null || b.getProduct() == product)
                .filter(b -> tenantId == null || tenantId.equals(b.getTenantId()))
                .filter(b -> environment == null || b.getEnvironment() == environment)
                .toList();

        Map<String, String> names = new HashMap<>();
        List<String> tenantIds = rows.stream().map(PlatformBooking::getTenantId).filter(Objects::nonNull).distinct().toList();
        if (!tenantIds.isEmpty()) {
            tenantRepository.findAllById(tenantIds).forEach(t -> names.put(t.getId(), t.getAgencyName()));
        }
        List<PnlRow> byAgency = group(rows, PlatformBooking::getTenantId, id -> names.getOrDefault(id, id));
        byAgency.sort(Comparator.comparing(r -> r.getLabel() == null ? "" : r.getLabel(), String.CASE_INSENSITIVE_ORDER));
        List<PnlRow> byProduct = group(rows, b -> String.valueOf(b.getProduct()), Function.identity());
        byProduct.sort(Comparator.comparing(PnlRow::getKey));
        return PnlResponse.builder().from(from).to(to).totals(row("ALL", "All bookings", rows))
                .byAgency(byAgency).byProduct(byProduct).build();
    }

    private static List<PnlRow> group(List<PlatformBooking> rows, Function<PlatformBooking, String> key, Function<String, String> label) {
        Map<String, List<PlatformBooking>> groups = rows.stream()
                .collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
        return groups.entrySet().stream().map(e -> row(e.getKey(), label.apply(e.getKey()), e.getValue()))
                .collect(Collectors.toCollection(java.util.ArrayList::new));
    }

    private static PnlRow row(String key, String label, List<PlatformBooking> rows) {
        BigDecimal gross = sum(rows, PlatformBooking::getGross);
        BigDecimal gmv = sum(rows, PlatformBooking::getDisplayed);
        return PnlRow.builder().key(key).label(label)
                .bookings(rows.size())
                .cancellations((int) rows.stream().filter(b -> b.getStatus() == PlatformBookingStatus.CANCELLED).count())
                .gmv(gmv).gross(gross).discountsGiven(gross.subtract(gmv))
                .commissionPaid(sum(rows, PlatformBooking::getAgencyCommission))
                .tdsWithheld(sum(rows, PlatformBooking::getTds))
                .earnedEstimate(sum(rows.stream().filter(b -> b.getVoyraEarnedSource() != VoyraEarnedSource.STATEMENT).toList(),
                        PlatformBooking::getVoyraEarned))
                .earnedStatement(sum(rows.stream().filter(b -> b.getVoyraEarnedSource() == VoyraEarnedSource.STATEMENT).toList(),
                        PlatformBooking::getVoyraEarned))
                .reversals(sum(rows, PlatformBooking::getVoyraEarnedReversal))
                .voyraNet(sum(rows, PlatformBooking::getVoyraNet))
                .build();
    }

    private static BigDecimal sum(List<PlatformBooking> rows, Function<PlatformBooking, BigDecimal> field) {
        return rows.stream().map(field).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
