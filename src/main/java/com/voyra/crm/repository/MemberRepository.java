package com.voyra.crm.repository;

import com.voyra.crm.entity.Member;
import com.voyra.crm.enums.MemberType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MemberRepository extends JpaRepository<Member, String> {

    List<Member> findByClientIdAndIsActiveTrue(String clientId);

    List<Member> findByClientId(String clientId);

    Optional<Member> findByMemberIdAndClientId(String memberId, String clientId);

    /** The client's own member row - the agency's point of contact. Exactly one per client. */
    Optional<Member> findByClientIdAndTypeAndIsActiveTrue(String clientId, MemberType type);

    List<Member> findByPhoneEndingWith(String phoneSuffix);

    long countByClientIdAndIsActiveTrue(String clientId);
}
