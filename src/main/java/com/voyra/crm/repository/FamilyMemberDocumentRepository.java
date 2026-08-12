package com.voyra.crm.repository;

import com.voyra.crm.entity.FamilyMemberDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FamilyMemberDocumentRepository extends JpaRepository<FamilyMemberDocument, String> {

    List<FamilyMemberDocument> findByFamilyMemberId(String familyMemberId);
}
