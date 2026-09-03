package com.voyra.crm.util;

import com.voyra.crm.entity.LeadService;
import com.voyra.crm.enums.ServiceType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ports {@code serviceInstanceLabel} from the frontend's {@code lib/serviceCatalog.ts} onto the
 * server, so both sides agree on a service's display name without either trusting the other's
 * computation. {@code lead_service.label} is stored, not computed on read (the cross-lead
 * Services board never loads a service's sibling set), so any write that adds, removes or
 * renames a service on a lead must call {@link #deriveAll} for that lead's full service list and
 * persist every label it sets, not only the one that changed.
 *
 * <p>The naming ladder, per docs/LLD_LEAD_MANAGEMENT.md §4.2:
 * <ol>
 *   <li>Exactly one instance of this type on the lead → the plain type label ("Hotel").</li>
 *   <li>Otherwise, the type's natural source field (Flight: first sector's {@code from–to};
 *       Hotel: city; Visa: country; Transfer: pickup) → {@code "Hotel — Phuket"}.</li>
 *   <li>Two siblings share that same source → append the 1-based position among just those
 *       siblings, from the second occurrence on: {@code "Hotel — Phuket (2)"}.</li>
 *   <li>The source field is blank → positional fallback among all same-type siblings:
 *       {@code "Hotel 2"}.</li>
 * </ol>
 */
public final class ServiceLabelDeriver {

    private ServiceLabelDeriver() {
    }

    /** Mutates {@code label} on every element, grouped by type. Callers pass the lead's full service list. */
    public static void deriveAll(List<LeadService> services) {
        Map<ServiceType, List<LeadService>> byType = services.stream()
                .collect(Collectors.groupingBy(LeadService::getType, LinkedHashMap::new, Collectors.toList()));
        byType.forEach(ServiceLabelDeriver::deriveForType);
    }

    private static void deriveForType(ServiceType type, List<LeadService> siblings) {
        String base = plainLabel(type);
        if (siblings.size() < 2) {
            siblings.forEach(s -> s.setLabel(base));
            return;
        }

        Map<String, Long> sourceCounts = siblings.stream()
                .map(ServiceLabelDeriver::naturalSource)
                .filter(source -> source != null && !source.isBlank())
                .collect(Collectors.groupingBy(source -> source, Collectors.counting()));
        Map<String, Integer> seenSoFar = new HashMap<>();

        int position = 0;
        for (LeadService s : siblings) {
            position++;
            String source = naturalSource(s);
            if (source == null || source.isBlank()) {
                s.setLabel(base + " " + position);
                continue;
            }
            if (sourceCounts.getOrDefault(source, 0L) < 2) {
                s.setLabel(base + " — " + source);
                continue;
            }
            int n = seenSoFar.merge(source, 1, Integer::sum);
            s.setLabel(n > 1 ? base + " — " + source + " (" + n + ")" : base + " — " + source);
        }
    }

    private static String plainLabel(ServiceType type) {
        return switch (type) {
            case FLIGHT -> "Flight";
            case HOTEL -> "Hotel";
            case VISA -> "Visa";
            case TRANSFER -> "Transfer";
        };
    }

    private static String naturalSource(LeadService s) {
        return switch (s.getType()) {
            case FLIGHT -> firstSectorRoute(s);
            case HOTEL -> s.getHotelCity();
            case VISA -> s.getVisaCountry();
            case TRANSFER -> s.getTransferPickup();
        };
    }

    /** A round trip's leg 2 mirrors leg 1, so the first sector with both ends set names the flight either way. */
    private static String firstSectorRoute(LeadService s) {
        if (s.getFlightSectors() == null) {
            return null;
        }
        return s.getFlightSectors().stream()
                .filter(sector -> sector.getFrom() != null && !sector.getFrom().isBlank()
                        && sector.getTo() != null && !sector.getTo().isBlank())
                .map(sector -> sector.getFrom() + "–" + sector.getTo())
                .findFirst().orElse(null);
    }
}
