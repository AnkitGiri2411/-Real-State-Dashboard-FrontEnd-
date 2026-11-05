package com.meridian.realestate.service;

import com.meridian.realestate.config.AppProperties;
import com.meridian.realestate.domain.Permission;
import com.meridian.realestate.domain.RefreshToken;
import com.meridian.realestate.domain.Role;
import com.meridian.realestate.domain.RolePermission;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.UserRole;
import com.meridian.realestate.domain.UserStatus;
import com.meridian.realestate.dto.AuthDtos;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.RefreshTokenRepository;
import com.meridian.realestate.repository.UserRepository;
import com.meridian.realestate.security.AccessScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in, session renewal, sign-out and the password change.
 *
 * <p><b>Why this class exists.</b> Every rule about who may open a session, and what happens to the
 * session afterwards, is stated here in one place: how an address is matched, that the password is
 * checked before the account's status, that a refresh rotates the session and a reused token is
 * refused, and that changing a password ends every other session. Splitting those across a filter, a
 * controller and a repository is how a product ends up with two different definitions of "signed
 * in".</p>
 *
 * <p><b>The rule it protects - sign-in reveals nothing.</b> The account's status is checked strictly
 * <em>after</em> a successful password check, never before. Refusing a deactivated account earlier
 * would tell an unauthenticated caller that the address exists, which the sign-in path otherwise
 * refuses to reveal. That is why {@code SecurityConfig}'s {@code UserDetailsService} does not enforce
 * status, and why every failure - a wrong password and an unknown address alike - is answered with
 * one message of identical wording.</p>
 *
 * <p><b>The rule it protects - a session is single-use.</b> Only the SHA-256 hash of the refresh
 * token is stored, and every refresh consumes the row it was presented with by revoking it and
 * recording the replacement's hash against it. A token that has already been rotated is therefore
 * refused, which is precisely what makes a stolen token detectable: whichever client refreshes second
 * presents a spent token and is turned away, instead of both silently sharing one session.</p>
 *
 * <p><b>No credential is ever written down.</b> Nothing here logs, returns, stores or audits a
 * password, a password hash or a token value. The audit entries record that somebody signed in or
 * out, and who, never how.</p>
 */
@Service
public class AuthService {

  /** Refresh tokens are 48 random bytes, base64url-encoded; the guessable length is the whole point. */
  private static final int REFRESH_TOKEN_BYTES = 48;

  /** The floor the product enforces on a new password; raised by policy rather than on the wire. */
  private static final int MIN_PASSWORD_LENGTH = 8;

  /** {@code refresh_tokens.user_agent} is varchar(255). */
  private static final int USER_AGENT_MAX = 255;

  /** {@code refresh_tokens.created_by_ip} is varchar(60). */
  private static final int IP_MAX = 60;

  /** The only token type this product issues. */
  private static final String TOKEN_TYPE = "Bearer";

  private static final SecureRandom RANDOM = new SecureRandom();

  private final UserRepository userRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final AccessScope accessScope;
  private final AuditRecordingService audit;
  private final AppProperties properties;

  public AuthService(
      UserRepository userRepository,
      RefreshTokenRepository refreshTokenRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      AccessScope accessScope,
      AuditRecordingService audit,
      AppProperties properties) {
    this.userRepository = userRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.accessScope = accessScope;
    this.audit = audit;
    this.properties = properties;
  }

  /**
   * Sign in, and open a session.
   *
   * <p>The address is trimmed and lower-cased before lookup because the schema stores it that way
   * ({@code ck_users_email_lower}); matching the stored form exactly keeps the lookup an index access
   * rather than a scan with a function call on the column. Lookup deliberately includes archived
   * addresses - the sign-in path has to be able to resolve one in order to refuse it after the
   * password has been checked.</p>
   */
  @Transactional
  public AuthDtos.AuthTokens login(String email, String password, String ip, String userAgent) {
    String address = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);

    User user = userRepository.findByEmail(address).orElse(null);
    // The comparison runs only when there is an account, and the same failure follows whether the
    // account was missing or the password was wrong - so the response cannot be used to discover
    // which addresses exist.
    boolean passwordMatches =
        user != null && password != null && passwordEncoder.matches(password, user.getPasswordHash());
    if (!passwordMatches) {
      throw invalidCredentials();
    }

    // Strictly after the password. See the class note: an earlier refusal would reveal the address.
    assertCanSignIn(user);

    user.setLastLoginAt(LocalDateTime.now());
    userRepository.save(user);

    AuthDtos.AuthTokens tokens = issueSession(user, ip, userAgent);

    // The actor is named explicitly: the security context is not populated during a sign-in, so
    // inferring it here would leave every LOGIN entry without an actor.
    audit.record(
        user.getId(),
        user.getFullName(),
        AuditActions.IDENTITY,
        AuditActions.LOGIN,
        "Signed in: " + user.getFullName(),
        "USER",
        user.getId(),
        user.getFullName());

    return tokens;
  }

  /**
   * Exchange a refresh token for a new pair, rotating the session.
   *
   * <p>The presented row is revoked and the replacement's hash recorded against it. A token that has
   * already been revoked - i.e. rotated away - is refused with {@code REFRESH_TOKEN_REVOKED}, and an
   * unexpired row whose window has passed is refused with {@code REFRESH_TOKEN_EXPIRED}.</p>
   */
  @Transactional
  public AuthDtos.AuthTokens refresh(String refreshToken, String ip, String userAgent) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw refreshRevoked();
    }

    RefreshToken stored =
        refreshTokenRepository.findByTokenHash(sha256(refreshToken)).orElseThrow(AuthService::refreshRevoked);

    // Revocation is checked before expiry: a rotated token is the case worth reporting, and a token
    // that is both is one the account already ended.
    if (stored.getRevokedAt() != null) {
      throw refreshRevoked();
    }
    if (!stored.getExpiresAt().isAfter(LocalDateTime.now())) {
      throw refreshExpired();
    }

    User user = stored.getUser();
    if (user == null || user.getDeletedAt() != null) {
      throw refreshRevoked();
    }
    // A refresh mints a fresh access token, so the account must still be allowed to hold a session.
    assertCanSignIn(user);

    String replacement = mintRefreshToken(user, ip, userAgent);
    stored.setRevokedAt(LocalDateTime.now());
    stored.setReplacedByHash(sha256(replacement));
    refreshTokenRepository.save(stored);

    return new AuthDtos.AuthTokens(
        jwtService.generateAccessToken(user),
        replacement,
        TOKEN_TYPE,
        jwtService.accessTokenExpirationSeconds());
  }

  /**
   * End the session behind the presented token.
   *
   * <p>Idempotent: an unknown or already-revoked token is accepted silently, because a client that is
   * signing out has succeeded either way and a second attempt must not fail. The row survives as
   * revoked so the account's session list can still explain what happened.</p>
   */
  @Transactional
  public void logout(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      return;
    }

    RefreshToken stored = refreshTokenRepository.findByTokenHash(sha256(refreshToken)).orElse(null);
    if (stored == null) {
      return;
    }
    if (stored.getRevokedAt() == null) {
      stored.setRevokedAt(LocalDateTime.now());
      refreshTokenRepository.save(stored);
    }

    User user = stored.getUser();
    if (user != null) {
      audit.record(
          user.getId(),
          user.getFullName(),
          AuditActions.IDENTITY,
          AuditActions.LOGOUT,
          "Signed out: " + user.getFullName(),
          "USER",
          user.getId(),
          user.getFullName());
    }
  }

  /**
   * End every live session an account holds.
   *
   * <p>Used by the self-service "sign out everywhere" and by administration when a colleague's
   * access is withdrawn. Every live session row for the account is revoked in one statement so the
   * whole sign-out shares a single instant.</p>
   */
  @Transactional
  public void logoutEverywhere(UUID userId) {
    if (userId == null) {
      return;
    }

    refreshTokenRepository.revokeAllLiveTokensForUser(userId, LocalDateTime.now());

    User user = userRepository.findByIdAndDeletedAtIsNull(userId).orElse(null);
    if (user != null) {
      audit.record(
          user.getId(),
          user.getFullName(),
          AuditActions.IDENTITY,
          AuditActions.LOGOUT,
          "Signed out of all sessions: " + user.getFullName(),
          "USER",
          user.getId(),
          user.getFullName());
    }
  }

  /** The signed-in user, with the roles and permission codes the shell draws from. */
  @Transactional(readOnly = true)
  public AuthDtos.CurrentUserResponse currentUser() {
    User user = accessScope.requireUser();

    return new AuthDtos.CurrentUserResponse(
        user.getId(),
        user.getStaffCode(),
        user.getFirstName(),
        user.getLastName(),
        user.getEmail(),
        user.getPhone(),
        user.getDepartment(),
        user.getDesignation(),
        user.getStatus() == null ? null : user.getStatus().name(),
        user.isForcePasswordReset(),
        rolesOf(user),
        permissionsOf(user));
  }

  /**
   * Change the signed-in user's password and end every session.
   *
   * <p>The current password is re-verified even though the caller is already authenticated - a
   * password change is the one place a borrowed session should not carry. Every live refresh token
   * for the account is revoked, including the calling session's, because a password change is the
   * moment a person is entitled to believe nobody else still holds a session.</p>
   */
  @Transactional
  public void changePassword(String currentPassword, String newPassword, String confirmPassword) {
    User user = accessScope.requireUser();

    if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
      throw badRequest("INVALID_CREDENTIALS");
    }
    if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
      throw badRequest("WEAK_PASSWORD");
    }
    if (!newPassword.equals(confirmPassword)) {
      throw badRequest("PASSWORD_MISMATCH");
    }

    user.setPasswordHash(passwordEncoder.encode(newPassword));
    user.setForcePasswordReset(false);
    userRepository.save(user);

    refreshTokenRepository.revokeAllLiveTokensForUser(user.getId(), LocalDateTime.now());

    audit.record(
        user.getId(),
        user.getFullName(),
        AuditActions.SECURITY,
        AuditActions.PERMISSION_SENSITIVE,
        "Password changed: " + user.getFullName(),
        "USER",
        user.getId(),
        user.getFullName());
  }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /** Mints the access token and opens a fresh refresh session for it. */
  private AuthDtos.AuthTokens issueSession(User user, String ip, String userAgent) {
    String refreshToken = mintRefreshToken(user, ip, userAgent);
    return new AuthDtos.AuthTokens(
        jwtService.generateAccessToken(user),
        refreshToken,
        TOKEN_TYPE,
        jwtService.accessTokenExpirationSeconds());
  }

  /**
   * Opens one refresh session and returns its plain token.
   *
   * <p>The plain token is returned to hand to the client and is never stored: only its SHA-256 hash
   * goes into the row, so a database disclosure hands over no usable session.</p>
   */
  private String mintRefreshToken(User user, String ip, String userAgent) {
    byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

    RefreshToken row = new RefreshToken();
    row.setUser(user);
    row.setTokenHash(sha256(token));
    row.setExpiresAt(
        LocalDateTime.now().plusSeconds(properties.getAuth().getRefreshTokenExpirationSeconds()));
    row.setCreatedByIp(truncate(ip, IP_MAX));
    row.setUserAgent(truncate(userAgent, USER_AGENT_MAX));
    refreshTokenRepository.save(row);

    return token;
  }

  /** Refuses an account that may not hold a session, with the code that names why. */
  private static void assertCanSignIn(User user) {
    if (user.getStatus() == UserStatus.ACTIVE) {
      return;
    }
    String code = user.getStatus() == UserStatus.SUSPENDED ? "ACCOUNT_LOCKED" : "ACCOUNT_INACTIVE";
    throw ApiException.rule(code, ErrorMessages.friendly(code));
  }

  private static ApiException invalidCredentials() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", ErrorMessages.friendly("INVALID_CREDENTIALS"));
  }

  private static ApiException refreshRevoked() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_REVOKED", ErrorMessages.friendly("REFRESH_TOKEN_REVOKED"));
  }

  private static ApiException refreshExpired() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_EXPIRED", ErrorMessages.friendly("REFRESH_TOKEN_EXPIRED"));
  }

  private static ApiException badRequest(String code) {
    return new ApiException(HttpStatus.BAD_REQUEST, code, ErrorMessages.friendly(code));
  }

  /** Refresh tokens are stored only as a SHA-256 hash, so a database read cannot mint a session. */
  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is required and is always present", ex);
    }
  }

  /** Fits a value to its column rather than letting the write fail on an unusually long header. */
  private static String truncate(String value, int max) {
    if (value == null) return null;
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static List<String> rolesOf(User user) {
    TreeSet<String> roles = new TreeSet<>();
    user.getRoleAssignments().stream()
        .map(UserRole::getRole)
        .filter(Objects::nonNull)
        .map(Role::getName)
        .filter(Objects::nonNull)
        .forEach(roles::add);
    return List.copyOf(roles);
  }

  private static List<String> permissionsOf(User user) {
    TreeSet<String> codes = new TreeSet<>();
    user.getRoleAssignments().stream()
        .map(UserRole::getRole)
        .filter(Objects::nonNull)
        .flatMap(role -> role.getPermissionAssignments().stream())
        .map(RolePermission::getPermission)
        .filter(Objects::nonNull)
        .map(Permission::getCode)
        .filter(Objects::nonNull)
        .forEach(codes::add);
    return List.copyOf(codes);
  }
}
