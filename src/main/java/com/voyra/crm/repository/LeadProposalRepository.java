package com.voyra.crm.repository;

import com.voyra.crm.entity.LeadProposal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeadProposalRepository extends JpaRepository<LeadProposal, String> {

    List<LeadProposal> findByLeadId(String leadId);

    Optional<LeadProposal> findByIdAndLeadId(String id, String leadId);

    void deleteByIdAndLeadId(String id, String leadId);

    /** lead_proposal.supplier is a live reference stored as a name string, not an FK - see VendorService. */
    @Modifying
    @Query("UPDATE LeadProposal p SET p.supplier = :newName WHERE p.supplier = :oldName")
    void updateSupplierName(@Param("oldName") String oldName, @Param("newName") String newName);
}
