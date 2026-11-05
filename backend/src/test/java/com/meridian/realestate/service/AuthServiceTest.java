package com.meridian.realestate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.RefreshToken;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.UserStatus;
import com.meridian.realestate.dto.AuthDtos;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.RefreshTokenRepository;
import com.meridian.realestate.repository.UserRepository;
import com.meridian.realestate.security.AccessScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Sign-in, refresh and the password change, with the repositories and collaborators mocked and no
 * database.
 *
 * <p>The rules asserted here are the ones a wrong refactor would quietly break: the address is
 * lower-cased before lookup; a wrong password and an unknown address fail with the <em>same</em>
 * answer; the account's status is checked only <em>after</em> the password matches, so a deactivated
 * account is refused as inactive rather than revealed as absent; an already-rotated refresh token is
 * refused; and a refresh consumes the row it was presented with and records the replacement against
 * it. Each of those is a promise to somebody - a staff member who mistyped, or an attacker probing
 * the address list - and each is cheap to break and expensive to notice.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService: sign-in, refresh and the password change")
class AuthServiceTest {

  private static final String ADDRESS = "owner@meridianestates.example";

  @Mock private UserRepository userRepository;
  @Mock private RefreshTokenRepository refreshTokenRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private JwtService jwtService;
  @Mock private AccessScope accessScope;
  @Mock private AuditRecordingService audit;

  private AuthService service;

  @BeforeEach
  void setUp() {
    service =
        new AuthService(
            userRepository,
            refreshTokenRepository,
            passwordEncoder,
            jwtService,
            accessScope,
            audit,
            new AppProperties());
  }

  private static User activeUser() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setStaffCode("ME-0001");
    user.setEmail(ADDRESS);
    user.setFirstName("Aditya");
    user.setLastName("Raghunathan");
    user.setPasswordHash("test-password-hash");
    user.setStatus(UserStatus.ACTIVE);
    return user;
  }

  /** The SHA-256 hex the service stores, replicated here because the service keeps it private. */
  private static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  // ------------------------------------------------------------------ sign-in

  @Test
  @DisplayName("a wrong password is refused with INVALID_CREDENTIALS and no session is opened")
  void aWrongPasswordIsRefusedWithoutIssuingAToken() {
    User user = activeUser();
    when(userRepository.findByEmail(ADDRESS)).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong-password", user.getPasswordHash())).thenReturn(false);

    assertThatThrownBy(() -> service.login(ADDRESS, "wrong-password", "203.0.113.7", "JUnit"))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).getCode())
        .isEqualTo("INVALID_CREDENTIALS");

    verify(jwtService, never()).generateAccessToken(any());
    verify(refreshTokenRepository, never()).save(any());
  }

  @Test
  @DisplayName("the address is lower-cased and trimmed before the lookup")
  void theAddressIsLowerCasedAndTrimmedBeforeLookup() {
    User user = activeUser();
    when(userRepository.findByEmail(ADDRESS)).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("testing", user.getPasswordHash())).thenReturn(true);
    when(jwtService.generateAccessToken(user)).thenReturn("access-token");
    when(jwtService.accessTokenExpirationSeconds()).thenReturn(900L);

    AuthDtos.AuthTokens tokens =
        service.login("  Owner@MeridianEstates.Example  ", "testing", "203.0.113.7", "JUnit");

    assertThat(tokens.accessToken()).isEqualTo("access-token");
    assertThat(tokens.tokenType()).isEqualTo("Bearer");
    assertThat(tokens.expiresInSeconds()).isEqualTo(900L);
    assertThat(tokens.refreshToken()).isNotBlank();
    verify(userRepository).findByEmail(ADDRESS);
  }

  @Test
  @DisplayName("an active account with the correct password is let in and last_login_at is stamped")
  void aCorrectSignInIsRecorded() {
    User user = activeUser();
    when(userRepository.findByEmail(ADDRESS)).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("testing", user.getPasswordHash())).thenReturn(true);
    when(jwtService.generateAccessToken(user)).thenReturn("access-token");
    when(jwtService.accessTokenExpirationSeconds()).thenReturn(900L);

    service.login(ADDRESS, "testing", "203.0.113.7", "JUnit");

    assertThat(user.getLastLoginAt()).isNotNull();
    verify(userRepository).save(user);
  }

  @Test
  @DisplayName("an inactive account with the CORRECT password is refused as inactive")
  void anInactiveAccountIsRefusedAfterThePasswordMatches() {
    User user = activeUser();
    user.setStatus(UserStatus.INACTIVE);
    when(userRepository.findByEmail(ADDRESS)).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("testing", user.getPasswordHash())).thenReturn(true);

    assertThatThrownBy(() -> service.login(ADDRESS, "testing", "203.0.113.7", "JUnit"))
        .isInstanceOf(ApiException.class)
        .satisfies(
            ex -> {
              ApiException api = (ApiException) ex;
              assertThat(api.getCode()).isEqualTo("ACCOUNT_INACTIVE");
              assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            });

    verify(jwtService, never()).generateAccessToken(any());
  }

  @Test
  @DisplayName("a suspended account with the CORRECT password is refused as locked")
  void aSuspendedAccountIsRefusedAsLocked() {
    User user = activeUser();
    user.setStatus(UserStatus.SUSPENDED);
    when(userRepository.findByEmail(ADDRESS)).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("testing", user.getPasswordHash())).thenReturn(true);

    assertThatThrownBy(() -> service.login(ADDRESS, "testing", "203.0.113.7", "JUnit"))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).getCode())
        .isEqualTo("ACCOUNT_LOCKED");
  }

  @Test
  @DisplayName("an unknown address fails with exactly the same answer as a wrong password")
  void anUnknownAddressFailsExactlyLikeAWrongPassword() {
    when(userRepository.findByEmail("nobody@meridianestates.example")).thenReturn(Optional.empty());
    ApiException unknown =
        catchThrowableOfType(
            () ->
                service.login("nobody@meridianestates.example", "testing", "203.0.113.7", "JUnit"),
            ApiException.class);

    User user = activeUser();
    when(userRepository.findByEmail(ADDRESS)).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("testing", user.getPasswordHash())).thenReturn(false);
    ApiException wrong =
        catchThrowableOfType(
            () -> service.login(ADDRESS, "testing", "203.0.113.7", "JUnit"), ApiException.class);

    assertThat(unknown.getCode()).isEqualTo("INVALID_CREDENTIALS");
    assertThat(unknown.getCode()).isEqualTo(wrong.getCode());
    assertThat(unknown.getMessage()).isEqualTo(wrong.getMessage());
    assertThat(unknown.getStatus()).isEqualTo(wrong.getStatus());
  }

  // ------------------------------------------------------------------ refresh

  @Test
  @DisplayName("an already-rotated refresh token is refused")
  void aRevokedRefreshTokenIsRefused() {
    RefreshToken rotated = new RefreshToken();
    rotated.setTokenHash(hash("rotated-away"));
    rotated.setExpiresAt(LocalDateTime.now().plusDays(30));
    rotated.setRevokedAt(LocalDateTime.now().minusMinutes(1));
    when(refreshTokenRepository.findByTokenHash(hash("rotated-away")))
        .thenReturn(Optional.of(rotated));

    assertThatThrownBy(() -> service.refresh("rotated-away", "203.0.113.7", "JUnit"))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).getCode())
        .isEqualTo("REFRESH_TOKEN_REVOKED");
  }

  @Test
  @DisplayName("an expired refresh token is refused as expired")
  void anExpiredRefreshTokenIsRefused() {
    RefreshToken expired = new RefreshToken();
    expired.setTokenHash(hash("stale"));
    expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    when(refreshTokenRepository.findByTokenHash(hash("stale"))).thenReturn(Optional.of(expired));

    assertThatThrownBy(() -> service.refresh("stale", "203.0.113.7", "JUnit"))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).getCode())
        .isEqualTo("REFRESH_TOKEN_EXPIRED");
  }

  @Test
  @DisplayName("a refresh rotates: the old row is revoked and the replacement hash is stored")
  void refreshingRotatesTheSession() {
    User user = activeUser();
    RefreshToken live = new RefreshToken();
    live.setUser(user);
    live.setTokenHash(hash("current-token"));
    live.setExpiresAt(LocalDateTime.now().plusDays(30));
    when(refreshTokenRepository.findByTokenHash(hash("current-token")))
        .thenReturn(Optional.of(live));
    when(jwtService.generateAccessToken(user)).thenReturn("new-access");
    when(jwtService.accessTokenExpirationSeconds()).thenReturn(900L);

    AuthDtos.AuthTokens tokens = service.refresh("current-token", "203.0.113.7", "JUnit");

    assertThat(tokens.accessToken()).isEqualTo("new-access");
    assertThat(tokens.refreshToken()).isNotEqualTo("current-token");

    assertThat(live.getRevokedAt()).isNotNull();
    assertThat(live.getReplacedByHash()).isEqualTo(hash(tokens.refreshToken()));
    verify(refreshTokenRepository).save(live);
  }

  // ------------------------------------------------------------------ sign-out

  @Test
  @DisplayName("signing out revokes the presented session")
  void logoutRevokesThePresentedSession() {
    User user = activeUser();
    RefreshToken live = new RefreshToken();
    live.setUser(user);
    live.setTokenHash(hash("live-token"));
    live.setExpiresAt(LocalDateTime.now().plusDays(30));
    when(refreshTokenRepository.findByTokenHash(hash("live-token"))).thenReturn(Optional.of(live));

    service.logout("live-token");

    assertThat(live.getRevokedAt()).isNotNull();
    verify(refreshTokenRepository).save(live);
  }

  @Test
  @DisplayName("signing out everywhere revokes every live session for the account")
  void logoutEverywhereRevokesEveryLiveSession() {
    User user = activeUser();
    when(userRepository.findByIdAndDeletedAtIsNull(user.getId())).thenReturn(Optional.of(user));

    service.logoutEverywhere(user.getId());

    verify(refreshTokenRepository)
        .revokeAllLiveTokensForUser(eq(user.getId()), any(LocalDateTime.class));
  }

  // ------------------------------------------------------------ password change

  @Test
  @DisplayName("a password change re-hashes and revokes every live session")
  void changingThePasswordRevokesEveryLiveSession() {
    User user = activeUser();
    when(accessScope.requireUser()).thenReturn(user);
    when(passwordEncoder.matches("old-password", user.getPasswordHash())).thenReturn(true);
    when(passwordEncoder.encode("a-new-password")).thenReturn("new-hash");

    service.changePassword("old-password", "a-new-password", "a-new-password");

    assertThat(user.getPasswordHash()).isEqualTo("new-hash");
    assertThat(user.isForcePasswordReset()).isFalse();
    verify(userRepository).save(user);
    verify(refreshTokenRepository)
        .revokeAllLiveTokensForUser(eq(user.getId()), any(LocalDateTime.class));
  }

  @Test
  @DisplayName("a new password shorter than eight characters is refused")
  void aShortNewPasswordIsRefused() {
    User user = activeUser();
    when(accessScope.requireUser()).thenReturn(user);
    when(passwordEncoder.matches("old-password", user.getPasswordHash())).thenReturn(true);

    assertThatThrownBy(() -> service.changePassword("old-password", "short", "short"))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).getCode())
        .isEqualTo("WEAK_PASSWORD");
  }

  @Test
  @DisplayName("a confirmation that does not match is refused")
  void aMismatchedConfirmationIsRefused() {
    User user = activeUser();
    when(accessScope.requireUser()).thenReturn(user);
    when(passwordEncoder.matches("old-password", user.getPasswordHash())).thenReturn(true);

    assertThatThrownBy(
            () -> service.changePassword("old-password", "a-new-password", "a-different-one"))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).getCode())
        .isEqualTo("PASSWORD_MISMATCH");
  }

  @Test
  @DisplayName("an incorrect current password is refused before anything is changed")
  void anIncorrectCurrentPasswordIsRefused() {
    User user = activeUser();
    when(accessScope.requireUser()).thenReturn(user);
    when(passwordEncoder.matches("not-my-password", user.getPasswordHash())).thenReturn(false);

    assertThatThrownBy(
            () -> service.changePassword("not-my-password", "a-new-password", "a-new-password"))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).getCode())
        .isEqualTo("INVALID_CREDENTIALS");

    verify(userRepository, never()).save(any());
  }
}
