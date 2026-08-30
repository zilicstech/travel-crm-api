package com.voyra.crm.repository;

import com.voyra.crm.entity.LeadMember;
import com.voyra.crm.enums.LeadMemberStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface LeadMemberRepository extends JpaRepository<LeadMember, String> {

    List<LeadMember> findByLeadIdOrderByCreatedAtAsc(String leadId);

    /** One query for a whole page of leads - avoids an N+1 across the leads list. */
    List<LeadMember> findByLeadIdIn(Collection<String> leadIds);

    Optional<LeadMember> findByLeadIdAndMemberId(String leadId, String memberId);

    boolean existsByLeadIdAndMemberId(String leadId, String memberId);

    long countByLeadIdAndStatus(String leadId, LeadMemberStatus status);

    /** Keeps the denormalized member_name snapshot live-synced on Member rename. */
    @Modifying
    @Query("UPDATE LeadMember lm SET lm.memberName = :name WHERE lm.memberId = :memberId")
    void updateMemberNameForMember(@Param("memberId") String memberId, @Param("name") String name);
}
