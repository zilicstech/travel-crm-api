package com.voyra.crm.repository;

import com.voyra.crm.entity.MemberDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MemberDocumentRepository extends JpaRepository<MemberDocument, String> {

    List<MemberDocument> findByMemberId(String memberId);

    /** One query for a whole roster's documents - avoids an N+1 across the client detail view. */
    List<MemberDocument> findByMemberIdIn(Collection<String> memberIds);

    Optional<MemberDocument> findByIdAndMemberId(String id, String memberId);
}
