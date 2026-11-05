package com.meridian.realestate.exception;

import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.dto.ErrorResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The one place an exception becomes the product's error envelope.
 *
 * <p>Two rules hold throughout. First, a <b>client mistake is never reported as a server fault</b>:
 * a mistyped address, an unreadable body, a sort field that does not exist and a browser's
 * automatic {@code /favicon.ico} probe all answer 4xx with a code and a sentence, rather than
 * reaching the catch-all and filling the log with an ERROR-level trace. Second, <b>no internal
 * detail escapes</b>: no SQL, no stack trace, no class name, and no database constraint text.</p>
 *
 * <p>Every message a person reads comes from {@link ErrorMessages}, so a code added in the service
 * layer cannot arrive at a screen as shouting capitals.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
    return ResponseEntity.status(ex.getStatus())
        .body(ErrorResponse.of(ex.getCode(), ex.getMessage(), ex.getDetails()));
  }

  /**
   * Bean-validation failures, reported field by field.
   *
   * <p>The messages are the ones written on the request record's own annotations, so a form can
   * place each beside its input rather than showing one summary sentence.</p>
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
    List<String> details =
        ex.getBindingResult().getFieldErrors().stream()
            .map(GlobalExceptionHandler::describe)
            .toList();
    String message =
        details.isEmpty() ? ErrorMessages.friendly("VALIDATION_FAILED") : details.get(0);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of("VALIDATION_FAILED", message, details));
  }

  private static String describe(FieldError error) {
    String label = humanize(error.getField());
    String specific = error.getDefaultMessage();
    return specific == null || specific.isBlank()
        ? label + " is not valid"
        : label + ": " + specific;
  }

  /** {@code proposedRent} reads as "Proposed rent" beside a field. */
  private static String humanize(String field) {
    if (field == null || field.isBlank()) return "This value";
    String spaced = field.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ');
    return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1).toLowerCase();
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ErrorResponse.of("FORBIDDEN", ErrorMessages.friendly("FORBIDDEN")));
  }

  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex) {
    // Deliberately identical wording to every other sign-in failure, so the response cannot be
    // used to discover which e-mail addresses exist.
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(ErrorResponse.of("INVALID_CREDENTIALS", ErrorMessages.friendly("INVALID_CREDENTIALS")));
  }

  /**
   * A path that matches nothing.
   *
   * <p>Includes the browser's automatic {@code /favicon.ico} probe: a client mistake is not a
   * server fault, and treating it as one pollutes logs, monitoring and alerting.</p>
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse.of("NOT_FOUND", ErrorMessages.friendly("NOT_FOUND")));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(ErrorResponse.of("METHOD_NOT_ALLOWED", ErrorMessages.friendly("METHOD_NOT_ALLOWED")));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of("MALFORMED_BODY", ErrorMessages.friendly("MALFORMED_BODY")));
  }

  /**
   * A malformed path or query value.
   *
   * <p>A task number where a UUID belongs, or {@code page=abc}, is a 400 - never a 500.</p>
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    String name = ex.getName() == null ? "value" : ex.getName();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ErrorResponse.of(
                "INVALID_PATH_PARAMETER",
                "The value supplied for '" + name + "' is not valid",
                List.of(humanize(name) + " is not in the expected format")));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorResponse> handleMissingParam(
      MissingServletRequestParameterException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            ErrorResponse.of(
                "MISSING_PARAMETER",
                "A required request parameter is missing: " + ex.getParameterName()));
  }

  /**
   * A multipart request that omitted a part we require - an upload with no file.
   *
   * <p>Without this the dispatch fails before the controller body runs and the caller gets an
   * opaque 500, which is a confusing answer to "you forgot the file".</p>
   */
  @ExceptionHandler(MissingServletRequestPartException.class)
  public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException ex) {
    String code =
        "file".equals(ex.getRequestPartName()) ? "IMPORT_FILE_EMPTY" : "MISSING_PARAMETER";
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of(code, ErrorMessages.friendly(code)));
  }

  /**
   * An upload larger than the configured multipart limit.
   *
   * <p>Spring rejects it before the request reaches the application, so this is the only place the
   * limit can be reported clearly rather than as a container-level failure.</p>
   */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
    String code = "IMPORT_FILE_TOO_LARGE";
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(ErrorResponse.of(code, ErrorMessages.friendly(code)));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
      HttpMediaTypeNotSupportedException ex) {
    String code = "UNSUPPORTED_CONTENT_TYPE";
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(ErrorResponse.of(code, ErrorMessages.friendly(code)));
  }

  /** Any other malformed multipart request. */
  @ExceptionHandler(MultipartException.class)
  public ResponseEntity<ErrorResponse> handleMultipart(MultipartException ex) {
    String code = "IMPORT_FILE_CONTENT_INVALID";
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of(code, ErrorMessages.friendly(code)));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
    String code = ex.getReason() != null ? ex.getReason() : "INTERNAL_ERROR";
    String message = ErrorMessages.isKnown(code) ? ErrorMessages.friendly(code) : ex.getReason();
    if (message == null || message.isBlank()) message = ErrorMessages.friendly("INTERNAL_ERROR");
    return ResponseEntity.status(ex.getStatusCode())
        .body(ErrorResponse.of(code, message, List.of()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
    String code = ex.getMessage() == null ? "VALIDATION_FAILED" : ex.getMessage();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of(code, ErrorMessages.friendly(code)));
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
    String code = ex.getMessage() == null ? "INTERNAL_ERROR" : ex.getMessage();
    // A missing business-number series or a broken invariant is a configuration defect, not a
    // client mistake, so it is logged loudly even though the caller gets a calm sentence.
    log.error("Unfulfilled state: {}", code);
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse.of(code, ErrorMessages.friendly(code)));
  }

  /**
   * A unique or foreign-key constraint the database refused.
   *
   * <p>The constraint text is never passed on: it names tables and columns, which is exactly the
   * internal detail this handler exists to keep in. The service layer is expected to have checked
   * for the duplicate first and thrown a named {@link ApiException}; reaching here means it did not,
   * so it is logged as an error.</p>
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex) {
    log.error("Data integrity violation not caught by the service layer", ex);
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ErrorResponse.of(
                "CONSTRAINT_VIOLATION",
                "That change conflicts with other records in the portfolio"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
    log.error("Unhandled exception", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorResponse.of("INTERNAL_ERROR", ErrorMessages.friendly("INTERNAL_ERROR")));
  }
}
