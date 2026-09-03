package com.voyra.crm.repository;

import com.voyra.crm.entity.LeadVoucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeadVoucherRepository extends JpaRepository<LeadVoucher, String> {

    List<LeadVoucher> findByLeadIdOrderByCreatedAtDesc(String leadId);

    Optional<LeadVoucher> findByIdAndLeadId(String id, String leadId);
}
