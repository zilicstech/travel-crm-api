package com.voyra.crm.dto;

import com.voyra.crm.enums.InvoiceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientInvoiceResponse {

    private String id;
    private String customerId;
    private String customerName;
    private String agentId;
    private BigDecimal amount;
    private BigDecimal gst;
    private BigDecimal totalWithGst;
    private BigDecimal amountPaid;
    private BigDecimal pending;
    private InvoiceStatus status;
    private LocalDate invoiceDate;
    private LocalDate dueDate;
    private String paymentMode;
    private boolean overdue;
}
