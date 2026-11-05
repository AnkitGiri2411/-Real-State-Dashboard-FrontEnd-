package com.meridian.realestate.service;

import com.meridian.realestate.domain.Amenity;
import com.meridian.realestate.dto.AmenityDtos;
import com.meridian.realestate.repository.AmenityRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The amenity reference list.
 *
 * <p><b>Why this class exists.</b> An amenity - power backup, a lift, a clubhouse - is the product's
 * own vocabulary of facilities, seeded by the schema migration because every installation needs the
 * same list. It is read-only reference data: nothing creates, edits or archives one through the API,
 * so the service has reads and nothing else. Its job is to offer the facilities in the order and
 * grouping the property page presents them, so no screen has to know how they are stored.</p>
 *
 * <p><b>The rule it protects.</b> The list is offered in {@code display_order}, and grouped by the
 * {@code category} string the schema carries, so the property page and its filters are laid out
 * identically wherever they are drawn. Nothing here writes: a facility is a fact about what the
 * product understands, not a record a desk changes.</p>
 */
@Service
public class AmenityService {

  private final AmenityRepository amenityRepository;

  public AmenityService(AmenityRepository amenityRepository) {
    this.amenityRepository = amenityRepository;
  }

  /** Every facility, in display order. */
  @Transactional(readOnly = true)
  public List<AmenityDtos.AmenityView> list() {
    return amenityRepository.findAllByOrderByDisplayOrderAsc().stream()
        .map(AmenityDtos.AmenityView::from)
        .toList();
  }

  /**
   * Every facility, grouped by category and in display order.
   *
   * <p>The categories come back in the order they first appear in the display-ordered list, so the
   * grouping is stable and needs no second sort.</p>
   */
  @Transactional(readOnly = true)
  public List<AmenityDtos.AmenityGroup> grouped() {
    Map<String, List<AmenityDtos.AmenityView>> byCategory = new LinkedHashMap<>();
    for (Amenity amenity : amenityRepository.findAllByOrderByDisplayOrderAsc()) {
      byCategory
          .computeIfAbsent(categoryOf(amenity), ignored -> new ArrayList<>())
          .add(AmenityDtos.AmenityView.from(amenity));
    }
    return byCategory.entrySet().stream()
        .map(entry -> new AmenityDtos.AmenityGroup(entry.getKey(), List.copyOf(entry.getValue())))
        .toList();
  }

  /** One group of the property page - utility, access, security, parking, logistics, lifestyle. */
  @Transactional(readOnly = true)
  public List<AmenityDtos.AmenityView> byCategory(String category) {
    if (category == null || category.isBlank()) {
      return list();
    }
    return amenityRepository.findAllByCategoryOrderByDisplayOrderAsc(category.trim()).stream()
        .map(AmenityDtos.AmenityView::from)
        .toList();
  }

  private static String categoryOf(Amenity amenity) {
    String category = amenity.getCategory();
    return Objects.requireNonNullElse(category, "OTHER");
  }
}
