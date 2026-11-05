package com.meridian.realestate.repository;

import com.meridian.realestate.domain.ListingChannel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Lookup access to the channels a listing can be published on. Reference data: read-only. */
public interface ListingChannelRepository extends JpaRepository<ListingChannel, UUID> {

  Optional<ListingChannel> findByCode(String code);

  Optional<ListingChannel> findByCodeIgnoreCase(String code);

  /** The channels in the order the picker offers them. */
  List<ListingChannel> findAllByOrderByDisplayOrderAsc();

  /** Only the live channels, in offer order - what a new listing may be published on. */
  List<ListingChannel> findAllByActiveTrueOrderByDisplayOrderAsc();
}
