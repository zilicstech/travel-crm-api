package com.voyra.crm.repository;

import com.voyra.crm.entity.FamilyMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FamilyMemberRepository extends JpaRepository<FamilyMember, String> {

    List<FamilyMember> findByCustomerId(String customerId);
}
