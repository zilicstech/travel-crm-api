package com.voyra.crm.service;

import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingPaymentStatusUpdateRequest;
import com.voyra.crm.dto.BookingResponse;
import com.voyra.crm.dto.BookingStatusUpdateRequest;
import com.voyra.crm.dto.BookingUpdateRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.Customer;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.CustomerRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final CustomerRepository customerRepository;
    private final AgentRepository agentRepository;

    @Transactional
    public BookingResponse createBooking(BookingCreateRequest request) {
        AuthorResolver.AuthorInfo owner = resolveOwningAgent(request.getAgentId());
        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + request.getCustomerId()));

        Booking booking = Booking.builder()
                .id(generateUniqueBookingId())
                .customerId(customer.getId())
                .customerName(customer.getName())
                .agentId(owner.id())
                .agentName(owner.name())
                .type(request.getType())
                .destination(request.getDestination())
                .pnr(request.getPnr())
                .ticketNo(request.getTicketNo())
                .airline(request.getAirline())
                .supplier(request.getSupplier())
                .journeyDate(request.getJourneyDate())
                .returnDate(request.getReturnDate())
                .tripType(request.getTripType())
                .netCost(request.getNetCost())
                .sellingPrice(request.getSellingPrice())
                .profit(computeProfit(request.getSellingPrice(), request.getNetCost()))
                .bookingStatus(BookingStatus.PENDING)
                .paymentStatus(request.getPaymentStatus() != null ? request.getPaymentStatus()
                        : com.voyra.crm.enums.PaymentStatus.PENDING)
                .bookingDate(LocalDate.now())
                .createdDate(LocalDateTime.now())
                .build();
        bookingRepository.save(booking);

        log.info("Booking created: bookingId={}, agentId={}", booking.getId(), owner.id());
        return toResponse(booking);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> listBookings(BookingType typeFilter, BookingStatus statusFilter) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        List<Booking> base = principal.isAgent()
                ? bookingRepository.findByAgentId(principal.userId())
                : bookingRepository.findAll();
        return base.stream()
                .filter(b -> typeFilter == null || b.getType() == typeFilter)
                .filter(b -> statusFilter == null || b.getBookingStatus() == statusFilter)
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(String id) {
        return toResponse(findAccessibleBooking(id));
    }

    @Transactional
    public BookingResponse updateBooking(String id, BookingUpdateRequest request) {
        Booking booking = findAccessibleBooking(id);

        if (request.getPnr() != null) booking.setPnr(request.getPnr());
        if (request.getTicketNo() != null) booking.setTicketNo(request.getTicketNo());
        if (request.getAirline() != null) booking.setAirline(request.getAirline());
        if (request.getSupplier() != null) booking.setSupplier(request.getSupplier());
        if (request.getJourneyDate() != null) booking.setJourneyDate(request.getJourneyDate());
        if (request.getReturnDate() != null) booking.setReturnDate(request.getReturnDate());
        if (request.getTripType() != null) booking.setTripType(request.getTripType());
        if (request.getNetCost() != null) booking.setNetCost(request.getNetCost());
        if (request.getSellingPrice() != null) booking.setSellingPrice(request.getSellingPrice());
        // Profit is never client-trusted - always recomputed server-side from the current values.
        booking.setProfit(computeProfit(booking.getSellingPrice(), booking.getNetCost()));

        bookingRepository.save(booking);
        log.info("Booking updated: bookingId={}", id);
        return toResponse(booking);
    }

    @Transactional
    public BookingResponse updateStatus(String id, BookingStatusUpdateRequest request) {
        Booking booking = findAccessibleBooking(id);
        if (request.getBookingStatus() == BookingStatus.CANCELLED
                && (request.getCancelReason() == null || request.getCancelReason().isBlank())) {
            throw new IllegalArgumentException("A reason is required when cancelling a booking");
        }
        booking.setBookingStatus(request.getBookingStatus());
        if (request.getBookingStatus() == BookingStatus.CANCELLED) {
            booking.setCancelReason(request.getCancelReason());
            if (request.getRefundStatus() != null) {
                booking.setRefundStatus(request.getRefundStatus());
            }
        }
        bookingRepository.save(booking);
        log.info("Booking status updated: bookingId={}, status={}", id, request.getBookingStatus());
        return toResponse(booking);
    }

    @Transactional
    public BookingResponse updatePaymentStatus(String id, BookingPaymentStatusUpdateRequest request) {
        Booking booking = findAccessibleBooking(id);
        booking.setPaymentStatus(request.getPaymentStatus());
        bookingRepository.save(booking);
        return toResponse(booking);
    }

    private BigDecimal computeProfit(BigDecimal sellingPrice, BigDecimal netCost) {
        BigDecimal selling = sellingPrice != null ? sellingPrice : BigDecimal.ZERO;
        BigDecimal cost = netCost != null ? netCost : BigDecimal.ZERO;
        return selling.subtract(cost);
    }

    private AuthorResolver.AuthorInfo resolveOwningAgent(String requestedAgentId) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            Agent agent = agentRepository.findById(principal.userId())
                    .orElseThrow(() -> new IllegalStateException("Agent not found: " + principal.userId()));
            return new AuthorResolver.AuthorInfo(agent.getId(), agent.getName());
        }
        if (requestedAgentId == null || requestedAgentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required when an Owner creates a booking");
        }
        Agent agent = agentRepository.findByIdAndTenantId(requestedAgentId, principal.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found in this agency: " + requestedAgentId));
        return new AuthorResolver.AuthorInfo(agent.getId(), agent.getName());
    }

    private Booking findAccessibleBooking(String id) {
        Booking booking = bookingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + id));
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent() && !booking.getAgentId().equals(principal.userId())) {
            throw new AccessDeniedException("This booking is not assigned to you");
        }
        return booking;
    }

    private BookingResponse toResponse(Booking b) {
        return BookingResponse.builder()
                .id(b.getId()).customerId(b.getCustomerId()).customerName(b.getCustomerName())
                .agentId(b.getAgentId()).agentName(b.getAgentName()).type(b.getType())
                .destination(b.getDestination()).pnr(b.getPnr()).ticketNo(b.getTicketNo())
                .airline(b.getAirline()).supplier(b.getSupplier()).journeyDate(b.getJourneyDate())
                .returnDate(b.getReturnDate()).tripType(b.getTripType()).netCost(b.getNetCost())
                .sellingPrice(b.getSellingPrice()).profit(b.getProfit()).bookingStatus(b.getBookingStatus())
                .paymentStatus(b.getPaymentStatus()).bookingDate(b.getBookingDate())
                .cancelReason(b.getCancelReason()).refundStatus(b.getRefundStatus())
                .createdDate(b.getCreatedDate())
                .build();
    }

    private String generateUniqueBookingId() {
        int maxAttempts = 10;
        for (int i = 0; i < maxAttempts; i++) {
            String id = IdGenerator.generate6();
            if (!bookingRepository.existsById(id)) {
                return id;
            }
        }
        throw new IllegalStateException("Unable to generate unique booking id after " + maxAttempts + " attempts");
    }
}
