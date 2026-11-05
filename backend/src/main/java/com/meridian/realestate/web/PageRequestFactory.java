package com.meridian.realestate.web;

import com.meridian.realestate.exception.ApiException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Turns {@code page}, {@code size} and {@code sort} request parameters into a {@link Pageable},
 * refusing any sort field that is not on the endpoint's own whitelist.
 *
 * <p>Paging and sorting are honoured end to end. <b>No repository query in this product carries a
 * literal {@code order by}</b>, because a hard-coded order inside a paged query silently overrides
 * whatever the caller asked for and leaves the endpoint looking as though sorting is broken - the
 * page changes, the order does not.</p>
 *
 * <p><b>Why a whitelist rather than Spring's own {@code Pageable} resolver.</b> Handing the raw
 * {@code sort} parameter to Spring Data means an unrecognised field becomes a
 * {@code PropertyReferenceException} and therefore a <b>500</b> - an expected client mistake
 * reported as a server defect. It is also a surface worth closing deliberately rather than by
 * accident: the parameter names an entity <em>property</em>, so it reaches into the persistence
 * model, and a caller should not be able to probe the object graph with it. Every register here
 * therefore declares the handful of fields it actually offers, and anything else is answered as a
 * 400 that names the accepted fields.</p>
 *
 * <p>Usage:</p>
 * <pre>
 *   private static final PageRequestFactory PAGES =
 *       PageRequestFactory.sortedBy("createdAt,desc")
 *           .allow("createdAt", "createdAt")
 *           .allow("owner", "owner.displayName")
 *           .build();
 *
 *   Pageable pageable = PAGES.toPageable(page, size, sort);
 * </pre>
 *
 * <p>The API field name and the entity property may differ on purpose: the frontend sorts by
 * {@code owner} while the persistence model sorts by {@code owner.displayName}, and neither has to
 * know about the other.</p>
 */
public final class PageRequestFactory {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  /** API sort-field name to entity property path, in the order they will be reported. */
  private final Map<String, String> sortable;

  private final Sort fallback;

  private PageRequestFactory(Map<String, String> sortable, Sort fallback) {
    this.sortable = sortable;
    this.fallback = fallback;
  }

  /** Begin a whitelist whose default order is given as {@code "field,direction"}. */
  public static Builder sortedBy(String defaultSort) {
    return new Builder(defaultSort);
  }

  /**
   * Resolve a page request.
   *
   * @param page zero-based page index; null or negative means the first page
   * @param size rows per page; null falls back to the default and a larger value is capped
   * @param sort {@code "field"} or {@code "field,asc|desc"}; null means the endpoint's default
   * @throws ApiException 400 when the field is not on the whitelist or the direction is not
   *     {@code asc}/{@code desc}, so the caller gets a named reason instead of a 500
   */
  public Pageable toPageable(Integer page, Integer size, String sort) {
    int resolvedPage = page == null || page < 0 ? 0 : page;
    int resolvedSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
    return PageRequest.of(resolvedPage, resolvedSize, resolveSort(sort));
  }

  /** The sort the caller asked for, or the endpoint's default when none was given. */
  public Sort resolveSort(String sort) {
    if (sort == null || sort.isBlank()) return fallback;

    String[] parts = sort.split(",", 2);
    String requested = parts[0].trim();
    if (requested.isEmpty()) return fallback;

    String property = sortable.get(requested);
    if (property == null) {
      throw ApiException.badRequest(
          "The value '" + requested + "' is not a field this list can be sorted by",
          sortable.keySet().stream().map(field -> "sort: " + field).toList());
    }

    Sort.Direction direction = Sort.Direction.ASC;
    if (parts.length == 2) {
      String raw = parts[1].trim().toLowerCase(Locale.ROOT);
      if (!raw.isEmpty()) {
        if ("asc".equals(raw)) {
          direction = Sort.Direction.ASC;
        } else if ("desc".equals(raw)) {
          direction = Sort.Direction.DESC;
        } else {
          throw ApiException.badRequest(
              "The direction '" + parts[1].trim() + "' is not valid; use asc or desc",
              List.of("sort: asc", "sort: desc"));
        }
      }
    }
    return Sort.by(direction, property);
  }

  /** The fields this list accepts, for a caller that wants to offer them. */
  public Collection<String> sortableFields() {
    return Collections.unmodifiableSet(sortable.keySet());
  }

  /** Collects the whitelist. */
  public static final class Builder {

    private final Map<String, String> sortable = new LinkedHashMap<>();
    private final String defaultField;
    private final Sort.Direction defaultDirection;

    private Builder(String defaultSort) {
      String[] parts = defaultSort == null ? new String[0] : defaultSort.split(",", 2);
      if (parts.length == 0 || parts[0].isBlank()) {
        throw new IllegalArgumentException("A default sort field is required");
      }
      this.defaultField = parts[0].trim();
      this.defaultDirection =
          parts.length == 2 && "desc".equalsIgnoreCase(parts[1].trim())
              ? Sort.Direction.DESC
              : Sort.Direction.ASC;
    }

    /**
     * Offer a field for sorting.
     *
     * @param apiField the name the caller sends
     * @param property the entity property path it maps to, which may be nested
     */
    public Builder allow(String apiField, String property) {
      sortable.put(apiField, property);
      return this;
    }

    /** Offer several fields whose API name and property path are the same. */
    public Builder allow(String... fields) {
      for (String field : fields) {
        sortable.put(field, field);
      }
      return this;
    }

    public PageRequestFactory build() {
      if (!sortable.containsKey(defaultField)) {
        sortable.put(defaultField, defaultField);
      }
      return new PageRequestFactory(sortable, Sort.by(defaultDirection, defaultField));
    }

    /** The fields collected so far, so a caller can assert the default is present. */
    public List<String> fields() {
      return new ArrayList<>(sortable.keySet());
    }
  }
}
