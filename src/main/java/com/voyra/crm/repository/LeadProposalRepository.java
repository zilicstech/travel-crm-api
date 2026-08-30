package com.voyra.crm.repository;

import com.voyra.crm.entity.LeadProposal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeadProposalRepository extends JpaRepository<LeadProposal, String> {

    List<LeadProposal> findByLeadId(String leadId);

    Optional<LeadProposal> findByIdAndLeadId(String id, String leadId);

    void deleteByIdAndLeadId(String id, String leadId);
}
