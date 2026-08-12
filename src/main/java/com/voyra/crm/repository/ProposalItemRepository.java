package com.voyra.crm.repository;

import com.voyra.crm.entity.ProposalItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProposalItemRepository extends JpaRepository<ProposalItem, String> {

    List<ProposalItem> findByLeadId(String leadId);

    Optional<ProposalItem> findByIdAndLeadId(String id, String leadId);

    void deleteByIdAndLeadId(String id, String leadId);
}
