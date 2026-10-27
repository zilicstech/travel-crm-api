package com.voyra.crm.entity;

import com.voyra.crm.enums.StatementLineStatus;
import com.voyra.crm.enums.StatementLineType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One line of an imported statement and what the import did with it. At most one APPLIED line per (booking, type). */
@Entity
@Table(name = "platform_statement_line")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformStatementLine {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "import_id", nullable = false, length = 36)
    private String importId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "supplier_booking_id", length = 120)
    private String supplierBookingId;

    @Column(name = "pnr", length = 40)
    private String pnr;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 6)
    private StatementLineType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "commission", nullable = false, precision = 19, scale = 2)
    private BigDecimal commission;

    @Column(name = "line_date", nullable = false)
    private LocalDate lineDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private StatementLineStatus status;

    @Column(name = "reason", length = 300)
    private String reason;

    @Column(name = "platform_booking_id", length = 36)
    private String platformBookingId;
}
