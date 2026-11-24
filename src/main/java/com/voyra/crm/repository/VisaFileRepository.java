package com.voyra.crm.repository;

import com.voyra.crm.entity.VisaFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VisaFileRepository extends JpaRepository<VisaFile, String> {

    List<VisaFile> findByVisaId(String visaId);

    List<VisaFile> findByVisaIdIn(Collection<String> visaIds);

    Optional<VisaFile> findByVisaIdAndFieldId(String visaId, String fieldId);
}
