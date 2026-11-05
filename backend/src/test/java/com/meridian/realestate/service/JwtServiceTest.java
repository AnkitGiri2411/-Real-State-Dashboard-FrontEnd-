package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meridian.realestate.domain.Permission;
import com.meridian.realestate.domain.Role;
import com.meridian.realestate.domain.RolePermission;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.UserRole;
import io.jsonwebtoken.Claims;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Minting and verifying an access token, with no database and no Spring context.
 *
 * <p>What matters here is the token's contract, because three other components read it: the subject
 * is the account id {@code AccessScope} reloads against, the roles carry the {@code ROLE_} prefix on
 * the request (not in the claim) and the permissions are the raw codes {@code @PreAuthorize} checks.
 * The failure paths are asserted too - a token from another key, an expired one, a garbage string -
 * because the request filter relies on {@code isValid} returning {@code false} rather than throwing,
 * and a regression there would turn a bad token into a 500 instead of a 401.</p>
 */
@DisplayName("JwtService: minting and verifying the access token")
class JwtServiceTest {

  private static final String SECRET =
      "meridian-estates-test-signing-secret-value-at-least-32-bytes";
  private static final String OTHER_SECRET =
      "a-completely-different-signing-secret-value-at-least-32-bytes";

  private final JwtService service = new JwtService(SECRET, 900);

  private static Permission permission(String code) {
    Permission permission = new Permission();
    permission.setCode(code);
    permission.setModule("TEST");
    return permission;
  }

  /** A list-valued claim as strings, so no raw-type cast is needed at the assertion. */
  private static List<String> stringClaim(Claims claims, String name) {
    Object value = claims.get(name);
    return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
  }

  /** An account holding one role with two capabilities, as the seed data shapes one. */
  private static User agent() {
    Role role = new Role();
    role.setName("LEASING_AGENT");
    role.addPermissionAssignment(new RolePermission(role, permission("LEAD_VIEW")));
    role.addPermissionAssignment(new RolePermission(role, permission("LISTING_PUBLISH")));

    User user = new User();
    user.setId(UUID.randomUUID());
    user.setEmail("leasing@meridianestates.example");
    user.setFirstName("Sneha");
    user.setLastName("Kulkarni");
    user.addRoleAssignment(new UserRole(user, role));
    return user;
  }

  @Test
  @DisplayName("a generated token parses and carries the subject, roles, codes, e-mail and name")
  void generatedTokenParsesWithTheRightClaims() {
    User user = agent();
    String token = service.generateAccessToken(user);

    assertThat(service.isValid(token)).isTrue();

    Claims claims = service.parseClaims(token);
    assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
    assertThat(claims.getIssuer()).isEqualTo("meridian-estates");
    assertThat(stringClaim(claims, "roles")).containsExactly("LEASING_AGENT");
    assertThat(stringClaim(claims, "permissions"))
        .containsExactlyInAnyOrder("LEAD_VIEW", "LISTING_PUBLISH");
    assertThat(claims.get("email")).isEqualTo("leasing@meridianestates.example");
    assertThat(claims.get("name")).isEqualTo("Sneha Kulkarni");
  }

  @Test
  @DisplayName("permission codes are carried raw, never with a ROLE_ prefix")
  void permissionsAreNeverPrefixed() {
    Claims claims = service.parseClaims(service.generateAccessToken(agent()));

    assertThat(stringClaim(claims, "permissions"))
        .allMatch(code -> !code.startsWith("ROLE_"));
  }

  @Test
  @DisplayName("a token signed with a different key is invalid")
  void aTokenFromAnotherKeyIsInvalid() {
    JwtService foreignSigner = new JwtService(OTHER_SECRET, 900);

    assertThat(service.isValid(foreignSigner.generateAccessToken(agent()))).isFalse();
  }

  @Test
  @DisplayName("an expired token is invalid")
  void anExpiredTokenIsInvalid() {
    // A negative lifetime mints a token whose window has already closed.
    JwtService alreadyExpired = new JwtService(SECRET, -60);

    assertThat(service.isValid(alreadyExpired.generateAccessToken(agent()))).isFalse();
  }

  @Test
  @DisplayName("a garbage string is invalid, and isValid does not throw")
  void garbageIsInvalidWithoutThrowing() {
    assertThat(service.isValid("not-a-jwt")).isFalse();
    assertThat(service.isValid("a.b.c")).isFalse();
    assertThat(service.isValid("")).isFalse();
    assertThat(service.isValid(null)).isFalse();
  }

  @Test
  @DisplayName("a secret shorter than 32 bytes is refused when the service is built")
  void aShortSecretIsRefused() {
    assertThatThrownBy(() -> new JwtService("too-short-for-hs256", 900))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32");
  }

  @Test
  @DisplayName("the reported expiration is the one it was configured with")
  void reportsTheConfiguredExpiration() {
    assertThat(service.accessTokenExpirationSeconds()).isEqualTo(900);
  }
}
