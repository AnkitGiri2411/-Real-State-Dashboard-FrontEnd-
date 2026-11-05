package com.meridian.realestate.service;

import com.meridian.realestate.domain.Permission;
import com.meridian.realestate.domain.Role;
import com.meridian.realestate.domain.RolePermission;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Mints and verifies the short-lived access token.
 *
 * <p><b>Why this class exists.</b> Every request but sign-in, refresh and the readiness probe carries
 * a bearer token, and this is the one place that token is written and the one place it is read. The
 * token carries <em>identity and authorities</em> and nothing else: the account id as the subject,
 * the role names and the raw permission codes, plus the e-mail and name for convenience. It
 * deliberately does <b>not</b> carry what the caller is allowed to see -
 * {@link com.meridian.realestate.security.AccessScope} reloads the account from the database on every
 * request, so a withdrawn permission or a deactivated colleague takes effect on the next request
 * rather than when the token expires.</p>
 *
 * <p><b>The rule it protects.</b> The signing key is HMAC-SHA256 and is required to be at least 32
 * bytes. A shorter key is refused at construction with a sentence naming the reason, because a weak
 * key silently weakens every token the installation will ever issue and would be invisible in
 * production. The key is never logged; a log line that echoes a secret is itself the leak.</p>
 *
 * <p>Permissions are carried as their raw codes - never with a {@code ROLE_} prefix - because a
 * permission code is the authority {@code @PreAuthorize} checks, and the {@code ROLE_} prefix belongs
 * only to role names.</p>
 */
@Service
public class JwtService {

  /** The shortest key HS256 should be handed; 256 bits of entropy is the algorithm's own floor. */
  private static final int MINIMUM_KEY_BYTES = 32;

  /** Who the product says minted a token, for anyone reading the claims at the desk. */
  private static final String ISSUER = "meridian-estates";

  private final SecretKey key;
  private final long accessTokenExpirationSeconds;

  public JwtService(
      @Value("${jwt.secret:}") String secret,
      @Value("${app.auth.access-token-expiration-seconds:900}") long accessTokenExpirationSeconds) {

    byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MINIMUM_KEY_BYTES) {
      // Refusing to start is the only safe answer: a short key weakens every token the product will
      // ever issue. The message names the variable and the remedy, never the value.
      throw new IllegalStateException(
          "jwt.secret must be at least "
              + MINIMUM_KEY_BYTES
              + " bytes for HS256, but the configured value is "
              + bytes.length
              + " bytes. Set JWT_SECRET to a long random value (openssl rand -base64 48).");
    }
    this.key = Keys.hmacShaKeyFor(bytes);
    this.accessTokenExpirationSeconds = accessTokenExpirationSeconds;
  }

  /** How long a freshly minted access token lasts, so a caller can report it as {@code expiresIn}. */
  public long accessTokenExpirationSeconds() {
    return accessTokenExpirationSeconds;
  }

  /**
   * Mint an access token for a signed-in account.
   *
   * <p>The roles and permission codes are assembled from the account's assignments and are written
   * sorted and de-duplicated so two tokens for the same account are byte-identical in their
   * authority claims - which is what lets a token be compared or cached without surprise.</p>
   */
  public String generateAccessToken(User user) {
    Instant now = Instant.now();

    TreeSet<String> roles = new TreeSet<>();
    TreeSet<String> permissions = new TreeSet<>();
    user.getRoleAssignments().stream()
        .map(UserRole::getRole)
        .filter(Objects::nonNull)
        .forEach(
            role -> {
              if (role.getName() != null) roles.add(role.getName());
              role.getPermissionAssignments().stream()
                  .map(RolePermission::getPermission)
                  .filter(Objects::nonNull)
                  .map(Permission::getCode)
                  .filter(Objects::nonNull)
                  .forEach(permissions::add);
            });

    return Jwts.builder()
        .subject(user.getId().toString())
        .issuer(ISSUER)
        .claim("email", user.getEmail())
        .claim("name", user.getFullName())
        .claim("roles", List.copyOf(roles))
        .claim("permissions", List.copyOf(permissions))
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(accessTokenExpirationSeconds)))
        .signWith(key)
        .compact();
  }

  /**
   * True only for a token this service signed and that has not expired.
   *
   * <p>A malformed, tampered, expired or empty token is answered {@code false} rather than raised.
   * The request filter depends on that: it treats an invalid token as "no token" and continues
   * unauthenticated, so a bad token and an absent one produce the same single response shape.</p>
   */
  public boolean isValid(String token) {
    if (token == null || token.isBlank()) return false;
    try {
      parseClaims(token);
      return true;
    } catch (JwtException | IllegalArgumentException ex) {
      return false;
    }
  }

  /**
   * The verified claims of a token.
   *
   * <p>Throws {@link JwtException} for a malformed, tampered or expired token. Callers on a request
   * path must treat a throw as "unauthenticated" rather than let it escape as a 500 - the filter does
   * exactly that, and {@link #isValid(String)} exists so the common decision does not have to catch
   * one.</p>
   */
  public Claims parseClaims(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }
}
