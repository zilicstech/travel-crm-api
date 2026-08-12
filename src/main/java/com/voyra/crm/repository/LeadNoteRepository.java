package com.voyra.crm.repository;

import com.voyra.crm.entity.LeadNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeadNoteRepository extends JpaRepository<LeadNote, String> {

    List<LeadNote> findByLeadIdOrderByCreatedDateDesc(String leadId);

    long countByAuthorAgentId(String authorAgentId);

    @Modifying
    @Query("UPDATE LeadNote n SET n.authorName = :name WHERE n.authorAgentId = :agentId")
    void updateAuthorNameForAgent(@Param("agentId") String agentId, @Param("name") String name);
}
