package com.voyra.crm.repository;

import com.voyra.crm.entity.PlatformCancelRequestFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlatformCancelRequestFileRepository extends JpaRepository<PlatformCancelRequestFile, String> {

    List<PlatformCancelRequestFile> findByRequestIdInOrderByCreatedAtAsc(Collection<String> requestIds);

    Optional<PlatformCancelRequestFile> findByIdAndRequestId(String id, String requestId);
}
