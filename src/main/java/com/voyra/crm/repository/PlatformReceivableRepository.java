package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformReceivable;
import com.voyra.crm.enums.ReceivableStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlatformReceivableRepository extends JpaRepository<PlatformReceivable, String> {

    List<PlatformReceivable> findByTenantIdAndStatusOrderByCreatedAt(String tenantId, ReceivableStatus status);
}
