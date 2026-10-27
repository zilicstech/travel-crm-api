package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformStatementLine;
import com.voyra.crm.enums.StatementLineStatus;
import com.voyra.crm.enums.StatementLineType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformStatementLineRepository extends JpaRepository<PlatformStatementLine, String> {

    boolean existsByPlatformBookingIdAndTypeAndStatus(String platformBookingId, StatementLineType type, StatementLineStatus status);
}
