package com.voyra.crm.service;

import com.voyra.crm.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Reads from whichever tenant schema {@link com.voyra.crm.context.TenantContext} currently
 * points at, in a brand-new transaction/connection acquired AFTER the caller sets that
 * context (blueprint §3.5). Must live on its own bean - a self-invoked REQUIRES_NEW method
 * bypasses the Spring proxy and silently runs in the caller's existing transaction/schema.
 * Callers are responsible for the try {set} finally {restore/clear} around the call.
 */
@Service
@RequiredArgsConstructor
public class TenantScopedReadService {

    private final BookingRepository bookingRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public BigDecimal sumTotalBookingRevenue() {
        return bookingRepository.findAll().stream()
                .map(b -> b.getSellingPrice() != null ? b.getSellingPrice() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
