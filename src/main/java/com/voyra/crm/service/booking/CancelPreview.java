package com.voyra.crm.service.booking;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a whole-booking cancellation would post (spec 7.5): {@code refund} credited, {@code recall} (the commission
 * credited net of TDS) taken back, {@code net = refund - recall}. A null refund means the supplier did not say -
 * {@code refundToBeConfirmed} is then true and the cancellation goes through Voyra support. Hotels also carry the rate's
 * human-readable {@code cancellationPolicy} and {@code freeCancellationUntil} as reviewed (null for flights).
 */
public record CancelPreview(BigDecimal refund, BigDecimal recall, BigDecimal net, BigDecimal voyraFee,
                            boolean refundToBeConfirmed, String cancellationPolicy, Instant freeCancellationUntil) {
}
