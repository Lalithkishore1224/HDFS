package com.cloudstorage.access;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {

    long countByServedFromColdTrue();

    void deleteByFileId(UUID fileId);

    @Query("select avg(a.responseTimeMs) from AccessLog a")
    Double averageResponseTimeMs();
}
