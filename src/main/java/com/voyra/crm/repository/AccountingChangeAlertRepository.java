package com.voyra.crm.repository;

import com.voyra.crm.entity.AccountingChangeAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountingChangeAlertRepository extends JpaRepository<AccountingChangeAlert, String> {

    List<AccountingChangeAlert> findByAcknowledgedFalseOrderByRaisedAtDesc();

    List<AccountingChangeAlert> findByBookingIdOrderByRaisedAtDesc(@Param("bookingId") String bookingId);
}
