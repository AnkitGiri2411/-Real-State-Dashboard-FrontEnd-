package com.meridian.realestate.repository;

import com.meridian.realestate.domain.ViewingStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The state transitions of a viewing, newest first. */
public interface ViewingStatusHistoryRepository extends JpaRepository<ViewingStatusHistory, UUID> {

  List<ViewingStatusHistory> findAllByViewingIdOrderByChangedAtDesc(UUID viewingId);
}
