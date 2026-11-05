package com.meridian.realestate.exception;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * An expected failure that carries the response the caller should get.
 *
 * <p>Services throw this for anything the API has an answer for - a bad request, a refused
 * lifecycle transition, a record that is not there. One handler turns it into the product's
 * single error envelope, so a controller never builds an error response itself.</p>
 */
public class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final List<String> details;

  public ApiException(HttpStatus status, String code, String message, List<String> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details == null ? List.of() : details;
  }

  public ApiException(HttpStatus status, String code, String message) {
    this(status, code, message, List.of());
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }

  public List<String> getDetails() {
    return details;
  }

  public static ApiException badRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
  }

  /**
   * A bad request whose report names the accepted values.
   *
   * <p>Used where the caller supplied a value from a closed set the API defines - a sort field,
   * a filter code - and the honest answer is not just "no" but "these are the ones that exist".</p>
   */
  public static ApiException badRequest(String message, List<String> details) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, details);
  }

  /**
   * A domain rule refused the operation.
   *
   * <p>Answered as 409: the request was well formed and the caller is permitted, but the state
   * of the portfolio does not allow it - a lease that cannot be renewed from where it stands, an
   * over-allocation, an offer on a sale that already has an accepted one.</p>
   */
  public static ApiException rule(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }

  /** A rule refused the operation and the report names what would have been accepted. */
  public static ApiException rule(String code, String message, List<String> details) {
    return new ApiException(HttpStatus.CONFLICT, code, message, details);
  }

  /**
   * A rule that is not a conflict but an unprocessable instruction - a semantically invalid
   * request the caller must change before retrying. Answered as 422 so the frontend can tell it
   * apart from a state conflict.
   */
  public static ApiException unprocessable(String code, String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }

  public static ApiException notFound(String message) {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
  }

  public static ApiException forbidden(String message) {
    return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
  }

  public static ApiException unauthorized(String message) {
    return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
  }

  /** A duplicate that the database's own unique constraint would otherwise report opaquely. */
  public static ApiException duplicate(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }
}
