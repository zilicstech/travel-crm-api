package com.voyra.crm.repository;

import com.voyra.crm.entity.LeadTimeline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadTimelineRepository extends JpaRepository<LeadTimeline, String> {

    List<LeadTimeline> findByLeadIdOrderByCreatedAtDesc(String leadId);
}
