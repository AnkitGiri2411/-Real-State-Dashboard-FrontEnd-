package com.meridian.realestate.repository;

import com.meridian.realestate.domain.ListingStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The publication-state transitions of a listing, newest first. */
public interface ListingStatusHistoryRepository extends JpaRepository<ListingStatusHistory, UUID> {

  List<ListingStatusHistory> findAllByListingIdOrderByChangedAtDesc(UUID listingId);
}
