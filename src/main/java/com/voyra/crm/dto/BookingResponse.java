package com.voyra.crm.dto;

import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingResponse {

    private String id;
    private String customerId;
    private String customerName;
    private String agentId;
    private String agentName;
    private BookingType type;
    private String destination;
    private String pnr;
    private String ticketNo;
    private String airline;
    private String supplier;
    private LocalDate journeyDate;
    private LocalDate returnDate;
    private String tripType;
    private BigDecimal netCost;
    private BigDecimal sellingPrice;
    private BigDecimal profit;
    private BookingStatus bookingStatus;
    private PaymentStatus paymentStatus;
    private LocalDate bookingDate;
    private String cancelReason;
    private String refundStatus;
    private LocalDateTime createdDate;
}
