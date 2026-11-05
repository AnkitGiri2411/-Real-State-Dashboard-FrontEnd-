package com.meridian.realestate.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The error-message contract.
 *
 * <p><b>Why this test matters more than it looks.</b> {@code FRIENDLY} is built with
 * {@code Map.ofEntries}, which throws {@link IllegalArgumentException} on a duplicate key <em>during
 * class initialization</em>. Adding an entry that already exists is therefore not a cosmetic slip -
 * it stops the application from starting, and the failure surfaces as a bare
 * {@code ExceptionInInitializerError} with no hint of which key collided. Simply referencing the class
 * here runs that initializer, so a duplicate fails this suite immediately and by name.</p>
 *
 * <p>It also pins the two rules the class states about itself: every code it knows resolves to a real
 * sentence, and no code - known or not - is ever handed to a reader.</p>
 */
@DisplayName("ErrorMessages contract")
class ErrorMessagesTest {

  @Test
  @DisplayName("the map initializes, so it declares no duplicate key")
  void declaresNoDuplicateKey() {
    // Reaching this line at all means Map.ofEntries accepted the whole set. The assertion is the
    // explicit read, so the intent is legible rather than implied by a passing class load.
    assertThat(ErrorMessages.codes()).as("the message map must initialize").isNotEmpty();
  }

  @Test
  @DisplayName("every known code resolves to real wording, never to the fallback")
  void everyKnownCodeHasItsOwnWording() {
    String fallback = ErrorMessages.genericFallback();

    List<String> withoutWording =
        ErrorMessages.codes().stream()
            .filter((code) -> {
              String message = ErrorMessages.friendly(code);
              return message == null
                  || message.isBlank()
                  || message.equals(fallback)
                  || message.equals(code);
            })
            .sorted()
            .toList();

    assertThat(withoutWording)
        .as("these codes are mapped but would show the generic fallback, or the code itself")
        .isEmpty();
  }

  @Test
  @DisplayName("a code is never shown to a reader")
  void neverReturnsTheRawCode() {
    // The case that matters: a code the map has never heard of must not reach the screen as
    // SHOUTING_CAPS. A neutral apology is worse information but a far better experience.
    assertThat(ErrorMessages.friendly("SOME_UNMAPPED_CODE")).isEqualTo(ErrorMessages.genericFallback());
    assertThat(ErrorMessages.friendly("SOME_UNMAPPED_CODE")).doesNotContain("SOME_UNMAPPED_CODE");
  }

  @Test
  @DisplayName("a missing or blank code reads as the apology, not as a blank screen")
  void handlesNullAndBlank() {
    assertThat(ErrorMessages.friendly(null)).isEqualTo(ErrorMessages.genericFallback());
    assertThat(ErrorMessages.friendly("")).isEqualTo(ErrorMessages.genericFallback());
    assertThat(ErrorMessages.friendly("   ")).isEqualTo(ErrorMessages.genericFallback());
  }

  @Test
  @DisplayName("the codes the API actually throws carry wording")
  void coversTheCodesTheApiThrows() {
    // A representative slice of the codes the service layer throws, so a rename in a service that
    // forgets the map is caught here rather than by a reader seeing capitals.
    List<String> thrownByTheApi =
        List.of(
            "INVALID_CREDENTIALS", "ACCOUNT_INACTIVE", "ACCOUNT_LOCKED", "FORBIDDEN", "UNAUTHORIZED",
            "PROPERTY_NOT_FOUND", "PROPERTY_HAS_ACTIVE_LEASES", "UNIT_ALREADY_LET", "UNIT_NOT_LEASABLE",
            "OWNERSHIP_NOT_ONE_HUNDRED", "OWNERSHIP_EXCEEDS_ONE_HUNDRED",
            "UNIT_ALREADY_LET", "SCREENING_INCOMPLETE", "APPLICATION_ALREADY_OPEN_ON_UNIT",
            "LEAD_TRANSITION_NOT_ALLOWED", "LEAD_ASSIGN_INACTIVE_AGENT",
            "VIEWING_NOT_COMPLETABLE", "VIEWING_ALREADY_CANCELLED",
            "LEASE_TRANSITION_NOT_ALLOWED", "LEASE_END_BEFORE_START", "LEASE_TERM_OVERLAP",
            "PAYMENT_ALLOCATION_EXCEEDS_INVOICE", "PAYMENT_ALLOCATION_EXCEEDS_RECEIPT",
            "REFUND_EXCEEDS_RECEIPT", "INVOICE_LINES_DO_NOT_RECONCILE",
            "MAINTENANCE_TRANSITION_NOT_ALLOWED", "VENDOR_NOT_EMPANELLED",
            "EXPENSE_RECOVERY_EXCEEDS_TOTAL", "OFFER_ALREADY_ACCEPTED", "SALE_AMOUNT_REQUIRED",
            "IMPORT_FILE_TOO_LARGE", "IMPORT_IDEMPOTENCY_CONFLICT", "CONSTRAINT_VIOLATION");

    List<String> unknown =
        thrownByTheApi.stream().filter((code) -> !ErrorMessages.isKnown(code)).sorted().toList();

    assertThat(unknown).as("codes the API throws but the map does not know").isEmpty();
  }
}
