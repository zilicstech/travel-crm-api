package com.voyra.crm.repository;

import com.voyra.crm.entity.ProposalLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProposalLinkRepository extends JpaRepository<ProposalLink, String> {
}
