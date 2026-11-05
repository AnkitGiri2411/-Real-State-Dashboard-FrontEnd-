package com.meridian.realestate.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * The shape every paged endpoint returns, wrapped in {@link ApiResponse}.
 *
 * <p>Spring Data's {@link Page} is deliberately not returned directly: its JSON form is not a
 * stable contract (it carries extra fields whose names have moved between versions) and the
 * frontend's registers want exactly these six values - the rows, where they sit, how many
 * there are, and whether there is a next page to fetch.</p>
 */
public record PageResponse<T>(
    List<T> items, int page, int size, long totalElements, int totalPages, boolean hasNext) {

  public static <T> PageResponse<T> from(Page<T> page) {
    return new PageResponse<>(
        page.getContent(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages(),
        page.hasNext());
  }

  public static <T> PageResponse<T> of(List<T> items, int page, int size, long totalElements) {
    int totalPages = size <= 0 ? 1 : (int) Math.ceil((double) totalElements / size);
    return new PageResponse<>(items, page, size, totalElements, totalPages, page + 1 < totalPages);
  }

  /** An empty page, for a filter that matched nothing. */
  public static <T> PageResponse<T> empty(int page, int size) {
    return new PageResponse<>(List.of(), page, size, 0, 0, false);
  }
}
