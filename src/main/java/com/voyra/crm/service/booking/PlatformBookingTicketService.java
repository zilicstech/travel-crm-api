package com.voyra.crm.service.booking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.TicketDownloadResponse;
import com.voyra.crm.entity.PlatformBooking;
import com.voyra.crm.entity.PlatformBookingTicket;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.PlatformBookingStatus;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.enums.Sku;
import com.voyra.crm.enums.SupplierEnvironment;
import com.voyra.crm.exception.BookingConflictException;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.models.Entitlements;
import com.voyra.crm.models.booking.TicketLine;
import com.voyra.crm.repository.PlatformBookingRepository;
import com.voyra.crm.repository.PlatformBookingTicketRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.service.AgencyLogoLoader;
import com.voyra.crm.security.SystemActor;
import com.voyra.crm.service.AuthorResolver;
import com.voyra.crm.service.EntitlementService;
import com.voyra.crm.service.booking.ticket.Direction;
import com.voyra.crm.service.booking.ticket.FareLine;
import com.voyra.crm.service.booking.ticket.TicketFacts;
import com.voyra.crm.service.booking.ticket.TicketFare;
import com.voyra.crm.util.ETicketPdfRenderer;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Customer e-ticket downloads of a ticketed platform flight. Each download is a record (markup, fare lines and total
 * frozen at that moment); the PDF is rebuilt from the booking on every open and never stored, because it holds passenger
 * names the DPDP purge must be able to erase. Logs ids and amounts only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlatformBookingTicketService {

    private final PlatformBookingQueryService queryService;
    private final PlatformBookingRepository bookingRepository;
    private final PlatformBookingTicketRepository ticketRepository;
    private final TenantRepository tenantRepository;
    private final AuthorResolver authorResolver;
    private final AgencyLogoLoader agencyLogoLoader;
    private final PaxCodec paxCodec;
    private final ObjectMapper objectMapper;
    private final EntitlementService entitlementService;
    private final PlatformBookingMirrorService mirrorService;
    private final PlatformTransactionManager transactionManager;

    public record TicketPdf(byte[] bytes, String filename) {
    }

    /**
     * How pax_enc is stored by confirm: {passengers, contactEmail, contactPhone}. Only what the ticket prints is read
     * (name and type); passport, nationality and dates of birth are never mapped (owner ruling 2026-11-01).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredPax(List<PrintedPax> passengers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PrintedPax(String type, String title, String firstName, String lastName) {
    }

    /**
     * Records a download. Like confirm, no platform transaction is open while the CRM is written (blueprint 3.5): (1) a
     * short transaction checks the row and the markup; (2) when the agency has a CRM booking for it (CRM Core, not
     * READ_ONLY) its price becomes the published fare plus this markup, and a refusal there (below the invoiced price)
     * means no ticket; (3) a transaction locks the row, checks it again and saves the ticket ({@code seq} under the lock).
     * If (3) fails after (2) the price has moved without a ticket; downloading again records it.
     */
    public TicketDownloadResponse create(String bookingId, BigDecimal markup) {
        BigDecimal m = (markup == null ? BigDecimal.ZERO : markup).setScale(2, RoundingMode.HALF_UP);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        PlatformBooking checked = tx.execute(s -> {
            PlatformBooking b = queryService.visible(bookingId);
            fareLines(b, m);
            return b;
        });
        if (checked.getTenantBookingId() != null) {
            Entitlements crm = entitlementService.resolve(checked.getTenantId());
            if (crm.skus().contains(Sku.CRM_CORE) && !crm.readOnly()) {
                try {
                    AuthorResolver.AuthorInfo who = authorResolver.resolveCurrentAuthor();
                    mirrorService.setMarkup(checked, m, new SystemActor.Author(who.id(), who.name()));
                } catch (BookingConflictException e) {
                    if (!"MARKUP_NOT_APPLICABLE".equals(e.getCode())) {
                        throw e;
                    }
                    // the CRM row is not confirmed yet: the mirror's ticket phase prices this (now current) markup
                    log.info("E-ticket download for booking {} leaves its unconfirmed CRM booking to the mirror", bookingId);
                }
            }
        }
        PlatformBookingTicket t = tx.execute(s -> {
            // lock so two clicks get seq 1 and 2; only the caller's own agency's rows are ever locked
            PlatformBooking b = bookingRepository.findByIdAndTenantIdForUpdate(bookingId, checked.getTenantId())
                    .orElseThrow(() -> new NotFoundException("Booking not found"));
            List<FareLine> lines = fareLines(b, m);
            AuthorResolver.AuthorInfo who = authorResolver.resolveCurrentAuthor();
            return ticketRepository.save(PlatformBookingTicket.builder()
                    .id(UniqueIdResolver.resolve(ticketRepository::existsById))
                    .platformBookingId(b.getId()).tenantId(b.getTenantId())
                    .seq(ticketRepository.maxSeq(b.getId()) + 1)
                    .markup(m)
                    .fareLines(lines.stream().map(l -> {
                        Map<String, Object> line = new LinkedHashMap<>();
                        line.put("label", l.label());
                        line.put("amount", l.amount().toPlainString()); // a string keeps the exact scale through jsonb
                        return line;
                    }).toList())
                    .total(TicketFare.total(b.getGross(), m))
                    .createdBy(who.id()).createdByName(who.name())
                    .createdAt(Instant.now())
                    .build());
        });
        log.info("E-ticket download recorded: booking={} ticket={} seq={} markup={}", bookingId, t.getId(), t.getSeq(), m);
        return view(t);
    }

    /** A ticketed flight whose passengers are still held, and a markup of at most the published fare: its fare lines. */
    private static List<FareLine> fareLines(PlatformBooking b, BigDecimal m) {
        if (b.getProduct() != PricingProduct.FLIGHT || b.getStatus() != PlatformBookingStatus.TICKETED) {
            throw new BookingConflictException("TICKET_NOT_AVAILABLE", "A ticket can be downloaded only for a ticketed flight", Map.of());
        }
        if (b.getPaxPurgedAt() != null) {
            throw new BookingConflictException("PASSENGERS_PURGED", ETicketPdfRenderer.PURGED_TEXT, Map.of());
        }
        List<FareLine> lines = TicketFare.lines(b.getGross(), b.getBase(), m,
                TicketFacts.from(b.getItinerary(), b.getPricingSnapshot()).namedTaxes());
        if (m.compareTo(b.getGross()) > 0) {
            throw new IllegalArgumentException("Markup can be at most the published fare");
        }
        return lines;
    }

    /** Newest first. */
    @Transactional(readOnly = true)
    public List<TicketDownloadResponse> list(String bookingId) {
        PlatformBooking b = queryService.visible(bookingId);
        return ticketRepository.findByPlatformBookingIdAndTenantIdOrderBySeqDesc(b.getId(), b.getTenantId()).stream()
                .map(PlatformBookingTicketService::view).toList();
    }

    /** Rebuilds the PDF of one earlier download: the booking as it is now, the fare lines and total as they were frozen. */
    @Transactional(readOnly = true)
    public TicketPdf pdf(String bookingId, String ticketId) {
        PlatformBooking b = queryService.visible(bookingId);
        PlatformBookingTicket t = ticketRepository.findByIdAndPlatformBookingIdAndTenantId(ticketId, b.getId(), b.getTenantId())
                .orElseThrow(() -> new NotFoundException("Ticket not found"));
        Tenant agency = tenantRepository.findById(b.getTenantId()).orElseThrow();
        TicketFacts facts = TicketFacts.from(b.getItinerary(), b.getPricingSnapshot());
        boolean ticketed = b.getStatus() == PlatformBookingStatus.TICKETED;
        byte[] pdf = ETicketPdfRenderer.render(new ETicketPdfRenderer.ETicketData(agency.getAgencyName(), agency.getContactEmail(),
                agency.getContactPhone(), agency.getAddress(), agencyLogoLoader.load(agency), b.getId(),
                b.getTicketedAt() != null ? b.getTicketedAt() : b.getCreatedAt(),
                ticketed ? "Confirmed" : b.getStatus() == PlatformBookingStatus.CANCELLING ? "Cancellation in progress" : "Cancelled",
                !ticketed, b.getEnvironment() != SupplierEnvironment.PROD, facts,
                b.getPaxEnc() == null ? null : passengers(b, facts),
                t.getFareLines().stream().map(l -> objectMapper.convertValue(l, FareLine.class)).toList(), t.getTotal()));
        log.info("E-ticket opened: booking={} ticket={}", b.getId(), t.getId());
        return new TicketPdf(pdf, filename(b, t));
    }

    /** One row per passenger per direction, in the booking's passenger order (the order ticket_details' paxIndex counts in). */
    private List<ETicketPdfRenderer.PaxRow> passengers(PlatformBooking b, TicketFacts facts) {
        List<PrintedPax> pax = paxCodec.decrypt(b.getPaxEnc(), StoredPax.class).passengers();
        List<TicketLine> lines = b.getTicketDetails() == null ? List.of()
                : b.getTicketDetails().stream().map(l -> objectMapper.convertValue(l, TicketLine.class)).toList();
        boolean oneDirection = facts.directions().size() == 1;
        // flat ticket numbers hold no sector, so they are trusted only for a one-direction trip; the booking's PNR always applies
        List<String> flat = oneDirection && b.getTicketNumbers() != null ? b.getTicketNumbers() : List.of();
        List<ETicketPdfRenderer.PaxRow> out = new ArrayList<>();
        for (int i = 0; i < pax.size(); i++) {
            PrintedPax p = pax.get(i);
            for (Direction dir : facts.directions()) {
                out.add(new ETicketPdfRenderer.PaxRow(PlatformBookingQueryService.fullName(p.title(), p.firstName(), p.lastName()),
                        p.type(), dir.key(),
                        pnr(i, dir.key(), lines, b.getPnr()), TicketFacts.ticketNumber(i, dir.key(), lines, flat, pax.size())));
            }
        }
        return out;
    }

    private static String pnr(int pax, String sector, List<TicketLine> lines, String fallback) {
        return lines.stream().filter(l -> l.paxIndex() == pax && Objects.equals(l.sector(), sector) && l.pnr() != null)
                .map(TicketLine::pnr).findFirst().orElse(fallback);
    }

    /** e-ticket-<first PNR, non-alphanumerics as _, or the booking id>-<seq>.pdf */
    private static String filename(PlatformBooking b, PlatformBookingTicket t) {
        String pnr = b.getPnr() == null ? "" : b.getPnr().split("/", 2)[0].trim();
        return "e-ticket-" + (pnr.isEmpty() ? b.getId() : pnr.replaceAll("[^A-Za-z0-9]", "_")) + "-" + t.getSeq() + ".pdf";
    }

    private static TicketDownloadResponse view(PlatformBookingTicket t) {
        return TicketDownloadResponse.builder().id(t.getId()).seq(t.getSeq()).createdAt(t.getCreatedAt())
                .createdByName(t.getCreatedByName()).markup(t.getMarkup()).ticketPrice(t.getTotal()).build();
    }
}
