package com.meridian.realestate.dto;

/**
 * The envelope every successful response is wrapped in.
 *
 * <p>One shape for every endpoint means the reader has exactly one place to unwrap a payload
 * and one place to read a failure, rather than a different convention per controller. The
 * frontend's API client depends on this: it returns {@code data} and nothing else.</p>
 *
 * @param <T> the payload type
 */
public record ApiResponse<T>(boolean success, T data, String message) {

  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, data, null);
  }

  public static ApiResponse<Void> ok() {
    return new ApiResponse<>(true, null, null);
  }

  public static <T> ApiResponse<T> ok(T data, String message) {
    return new ApiResponse<>(true, data, message);
  }
}
