package com.voyra.crm.repository;

import com.voyra.crm.entity.MarkupDefault;
import com.voyra.crm.enums.ServiceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MarkupDefaultRepository extends JpaRepository<MarkupDefault, ServiceType> {
}
