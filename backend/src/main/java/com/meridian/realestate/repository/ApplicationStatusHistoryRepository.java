package com.meridian.realestate.repository;

import com.meridian.realestate.domain.ApplicationStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The state transitions of a rental application, newest first. */
public interface ApplicationStatusHistoryRepository extends JpaRepository<ApplicationStatusHistory, UUID> {

  List<ApplicationStatusHistory> findAllByApplicationIdOrderByChangedAtDesc(UUID applicationId);
}
