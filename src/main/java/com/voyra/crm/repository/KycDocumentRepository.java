package com.voyra.crm.repository;

import com.voyra.crm.entity.KycDocument;
import com.voyra.crm.enums.KycDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KycDocumentRepository extends JpaRepository<KycDocument, String> {

    List<KycDocument> findByApplicationIdOrderByCreatedAt(String applicationId);

    Optional<KycDocument> findByIdAndApplicationId(String id, String applicationId);

    long countByApplicationIdAndType(String applicationId, KycDocumentType type);
}
