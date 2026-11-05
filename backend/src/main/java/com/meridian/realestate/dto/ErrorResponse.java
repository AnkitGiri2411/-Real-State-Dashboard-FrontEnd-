package com.meridian.realestate.dto;

import java.util.List;

/**
 * The envelope every failure is reported in.
 *
 * <p>{@code details} carries per-field messages for a validation failure, so a form can show
 * "A unit with this code already exists" beside the field rather than a generic refusal. It is
 * never used to leak SQL, stack traces or class names.</p>
 */
public record ErrorResponse(boolean success, ErrorBody error) {

  public record ErrorBody(String code, String message, List<String> details) {}

  public static ErrorResponse of(String code, String message, List<String> details) {
    return new ErrorResponse(
        false, new ErrorBody(code, message, details == null ? List.of() : details));
  }

  public static ErrorResponse of(String code, String message) {
    return of(code, message, List.of());
  }
}
