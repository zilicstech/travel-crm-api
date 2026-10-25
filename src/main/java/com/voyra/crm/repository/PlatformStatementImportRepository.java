package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformStatementImport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformStatementImportRepository extends JpaRepository<PlatformStatementImport, String> {

    boolean existsByFileSha256(String fileSha256);
}
