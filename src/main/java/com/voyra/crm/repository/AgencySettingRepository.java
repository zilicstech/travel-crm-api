package com.voyra.crm.repository;

import com.voyra.crm.entity.AgencySetting;
import com.voyra.crm.enums.AgencySettingKind;
import com.voyra.crm.enums.ServiceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgencySettingRepository extends JpaRepository<AgencySetting, String> {

    List<AgencySetting> findByKindOrderBySortOrderAsc(AgencySettingKind kind);

    List<AgencySetting> findByKindAndServiceTypeOrderBySortOrderAsc(AgencySettingKind kind, ServiceType serviceType);

    boolean existsByKind(AgencySettingKind kind);

    boolean existsByKindAndNameIgnoreCase(AgencySettingKind kind, String name);
}
