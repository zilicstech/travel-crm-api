package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PlatformAdminRepository extends JpaRepository<PlatformAdmin, String> {

    Optional<PlatformAdmin> findByEmailIgnoreCase(String email);
}
