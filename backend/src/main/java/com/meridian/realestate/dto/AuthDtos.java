package com.meridian.realestate.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

/**
 * The sign-in, session and current-user payloads.
 *
 * <p>These records are the whole of the identity API's contract: what a sign-in submits, what a
 * session is handed back, and what the shell reads to draw the signed-in user. They are deliberately
 * narrow. The one field the account entity carries that must never appear here is
 * {@code passwordHash}: no response, no log line and no audit entry may carry it, and a payload with
 * nowhere to put it cannot leak it by accident.</p>
 *
 * <p>{@code permissions} on {@link CurrentUserResponse} is what the frontend filters its navigation
 * and controls on. It is a convenience for presentation only - every endpoint re-checks the
 * permission server-side - so hiding a menu entry is never the mechanism that protects an operation.
 * It is the raw permission code, never a {@code ROLE_} value.</p>
 */
public final class AuthDtos {

  private AuthDtos() {}

  /** What a sign-in submits. The address is matched lower-cased (see {@code AuthService}). */
  public record LoginRequest(
      @NotBlank(message = "E-mail is required")
          @Email(message = "Enter a valid e-mail address")
          String email,
      @NotBlank(message = "Password is required") String password) {}

  /** The refresh token a client presents to exchange a spent session for a new pair. */
  public record RefreshRequest(
      @NotBlank(message = "Refresh token is required") String refreshToken) {}

  /**
   * An issued session: the access token to present on every request, the refresh token to exchange,
   * and how long the access token lasts so a client can renew before it expires.
   */
  public record AuthTokens(
      String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {}

  /**
   * The signed-in user, as the shell reads it.
   *
   * <p>Carries the account's own profile and its effective authority - the union of the roles it
   * holds and the permission codes those roles grant. No credential, and no session hash, is ever
   * part of this.</p>
   */
  public record CurrentUserResponse(
      UUID id,
      String staffCode,
      String firstName,
      String lastName,
      String email,
      String phone,
      String department,
      String designation,
      String status,
      boolean forcePasswordReset,
      List<String> roles,
      List<String> permissions) {}

  /**
   * A password change.
   *
   * <p>The confirmation is part of the request on purpose: a mistyped new password would otherwise
   * lock the account out of itself. The minimum length is enforced in the service so the rule reads
   * in one place beside the hashing, not only on the wire.</p>
   */
  public record ChangePasswordRequest(
      @NotBlank(message = "Your current password is required") String currentPassword,
      @NotBlank(message = "A new password is required") String newPassword,
      @NotBlank(message = "Please confirm the new password") String confirmPassword) {}

  /** A plain acknowledgement for an operation that returns no payload of its own. */
  public record MessageResponse(String message) {}
}
