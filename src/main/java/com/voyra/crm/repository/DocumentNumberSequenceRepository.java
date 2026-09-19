package com.voyra.crm.repository;

import com.voyra.crm.entity.DocumentNumberSequence;
import com.voyra.crm.enums.DocumentKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DocumentNumberSequenceRepository extends JpaRepository<DocumentNumberSequence, String> {

    Optional<DocumentNumberSequence> findByDocumentKindAndFinancialYear(DocumentKind documentKind, String financialYear);
}
