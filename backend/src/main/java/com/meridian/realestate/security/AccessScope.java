package com.meridian.realestate.security;

import com.meridian.realestate.domain.AgentProfile;
import com.meridian.realestate.domain.Permission;
import com.meridian.realestate.domain.Role;
import com.meridian.realestate.domain.RolePermission;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.UserRole;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.UserRepository;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whose records the signed-in caller may see, as opposed to what they may do.
 *
 * <p><b>Why this class exists.</b> Two different questions are answered in two different places.
 * {@code @PreAuthorize} answers <b>what operation</b> is allowed, read from the permission codes
 * carried on the access token. This class answers <b>whose records</b> a caller may look at, for the
 * work that is personally owned - the leads, listings, viewings and sales one agent is working, the
 * maintenance on one desk's stock. A firm does not hide its own portfolio from its own staff: the
 * properties, units, owners and tenants are protected by permission alone. It is the personally
 * carried work that is scoped, and it is scoped here rather than in each service so the rule is
 * stated once.</p>
 *
 * <p><b>The rule it protects.</b> The user is reloaded from the database on every call and is
 * <em>never</em> taken from the token. That reload is the entire point: it is what makes a
 * deactivation, a role change or a withdrawn permission apply to the very next request instead of
 * whenever the access token happens to expire. A token records who somebody was at the moment it was
 * minted; this class refuses to act on that.</p>
 *
 * <p><b>Scope is not permission.</b> {@link #isCompanyWide()} is a scope decision, not a capability
 * one. A scoped agent holds no fewer capabilities for the work they actually do - they are simply
 * shown their own records rather than the whole firm's. That distinction matters: adding a permission
 * to a scoped role widens what they may <em>do</em>, but it never widens what they may <em>see</em>
 * beyond their own desk, and {@code LEASING_AGENT}, {@code SALES_AGENT} and {@code MAINTENANCE} stay
 * scoped however they are re-permissioned.</p>
 *
 * <p>Every read method opens its own read-only transaction so the lazily-mapped role, grant and
 * permission collections load inside it. With {@code open-in-view: false} there is no session held
 * for the request, so a scope check that reached into a lazy collection outside a transaction would
 * fail rather than quietly return nothing.</p>
 */
@Component
public class AccessScope {

  /**
   * The roles that see the whole firm's work, drawn from the eight roles {@code V2} seeds.
   *
   * <p>The eight seeded roles are {@code OWNER}, {@code ADMIN}, {@code PROPERTY_MANAGER},
   * {@code LEASING_AGENT}, {@code SALES_AGENT}, {@code ACCOUNTING}, {@code MAINTENANCE} and
   * {@code VIEWER}. Five of them see the whole portfolio, for a reason specific to each:</p>
   * <ul>
   *   <li>{@code OWNER} and {@code ADMIN} own the firm's records outright;</li>
   *   <li>{@code PROPERTY_MANAGER} runs the whole operation, the rent roll and the numbers;</li>
   *   <li>{@code ACCOUNTING} is the finance/audit role: the rent roll, the receipts and the
   *       read-only audit trail are firm-wide facts, never one agent's private work;</li>
   *   <li>{@code VIEWER} is read-only <em>across the portfolio</em> by definition - a board member,
   *       an auditor or a client with reporting access - so their read scope is the whole firm, even
   *       though they hold no writing capability at all.</li>
   * </ul>
   *
   * <p>{@code LEASING_AGENT}, {@code SALES_AGENT} and {@code MAINTENANCE} are deliberately absent:
   * the letting desk, the sales desk and facilities each carry their own book of work, and that is
   * exactly the work this class scopes.</p>
   */
  private static final Set<String> COMPANY_WIDE_ROLES =
      Set.of("OWNER", "ADMIN", "PROPERTY_MANAGER", "ACCOUNTING", "VIEWER");

  private final UserRepository userRepository;
  private final AgentProfileRepository agentProfiles;

  public AccessScope(UserRepository userRepository, AgentProfileRepository agentProfiles) {
    this.userRepository = userRepository;
    this.agentProfiles = agentProfiles;
  }

  /**
   * The signed-in user, reloaded from the database, or empty when the request is unauthenticated.
   *
   * <p>The security context's principal is the account id as a {@code String} - that is what
   * {@code JwtAuthenticationFilter} sets - so anything else (no authentication at all, the anonymous
   * principal, a blank value, or a value that is not a UUID) is answered with {@link Optional#empty}
   * rather than an error. An archived account is empty too: it may no longer act or be scoped.</p>
   */
  @Transactional(readOnly = true)
  public Optional<User> currentUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) return Optional.empty();

    Object principal = authentication.getPrincipal();
    if (!(principal instanceof String subject) || subject.isBlank()) return Optional.empty();

    try {
      return userRepository.findByIdAndDeletedAtIsNull(UUID.fromString(subject));
    } catch (IllegalArgumentException ex) {
      // The anonymous principal and any non-UUID subject land here; neither is a signed-in user.
      return Optional.empty();
    }
  }

  /** The signed-in user, or a 401 when there is none. */
  @Transactional(readOnly = true)
  public User requireUser() {
    return currentUser()
        .orElseThrow(() -> ApiException.unauthorized("Please sign in to continue"));
  }

  /** The signed-in account's id, or a 401 when there is none. */
  @Transactional(readOnly = true)
  public UUID requireUserId() {
    return requireUser().getId();
  }

  /** True when the caller holds the given permission code on any of their roles. */
  @Transactional(readOnly = true)
  public boolean hasPermission(String code) {
    if (code == null || code.isBlank()) return false;
    return currentUser().map(user -> holds(user, code)).orElse(false);
  }

  /** True when the caller holds <em>any</em> of the given permission codes. */
  @Transactional(readOnly = true)
  public boolean hasAnyPermission(String... codes) {
    if (codes == null || codes.length == 0) return false;
    return currentUser()
        .map(
            user ->
                Arrays.stream(codes)
                    .filter(Objects::nonNull)
                    .anyMatch(code -> holds(user, code)))
        .orElse(false);
  }

  /** True when the caller holds the named role on any of their assignments. */
  @Transactional(readOnly = true)
  public boolean hasRole(String roleName) {
    if (roleName == null || roleName.isBlank()) return false;
    return currentUser().map(user -> roleNames(user).contains(roleName)).orElse(false);
  }

  /**
   * True when the caller's scope covers the whole firm.
   *
   * <p>This is a scope decision and not a permission one: a scoped agent holds no fewer capabilities
   * for the work they do - they simply see their own records rather than every record. A caller with
   * no session is not company-wide.</p>
   */
  @Transactional(readOnly = true)
  public boolean isCompanyWide() {
    return currentUser().map(AccessScope::companyWide).orElse(false);
  }

  /**
   * Refuses the operation with a 403 unless the caller holds the given capability.
   *
   * <p>The method-security alternative, {@code @PreAuthorize("hasAuthority('CODE')")}, is preferred
   * on an endpoint because it is declared where the operation is. This is for the cases a domain
   * service has to state in its own body - a capability that only matters once a record has been
   * loaded, or a check that depends on a value the endpoint annotation cannot see.</p>
   */
  @Transactional(readOnly = true)
  public void requirePermission(String code) {
    if (!hasPermission(code)) {
      throw ApiException.forbidden(
          "Your account does not have permission to do that (" + code + ")");
    }
  }

  /**
   * Refuses the operation with a 403 unless the caller's scope covers the whole firm.
   *
   * @param what the operation, named as it will read back - "see the whole portfolio", "run this
   *     report" - so the refusal says what was attempted, not merely that it failed
   */
  @Transactional(readOnly = true)
  public void requireCompanyWide(String what) {
    if (!isCompanyWide()) {
      throw ApiException.forbidden(
          "Only a firm-wide role may " + what + "; your account sees only its own work");
    }
  }

  /**
   * True when the caller may see the work carried by the given agent profile.
   *
   * <p>A company-wide caller sees every agent's work. A scoped caller sees only the profile tied to
   * their own account, which is the one profile a member of staff can hold.</p>
   */
  @Transactional(readOnly = true)
  public boolean canSeeAgentWork(UUID agentProfileId) {
    if (agentProfileId == null) return false;
    return currentUser()
        .map(user -> companyWide(user) || isOwnProfile(user, agentProfileId))
        .orElse(false);
  }

  /** Refuses the operation with a 403 unless the caller may see the given agent's work. */
  @Transactional(readOnly = true)
  public void requireAgentOrCompanyWide(UUID agentProfileId) {
    if (!canSeeAgentWork(agentProfileId)) {
      throw ApiException.forbidden("That agent's work is not visible to your account");
    }
  }

  // ---------------------------------------------------------------------------
  // The lazy-graph reads, all of which run inside the annotated method above.
  // ---------------------------------------------------------------------------

  /** True when any of the user's roles is one of the firm-wide set. */
  private static boolean companyWide(User user) {
    return !java.util.Collections.disjoint(roleNames(user), COMPANY_WIDE_ROLES);
  }

  /** True when the user's own agent profile is the one being asked about. */
  private boolean isOwnProfile(User user, UUID agentProfileId) {
    return agentProfiles
        .findByUserIdAndDeletedAtIsNull(user.getId())
        .map(AgentProfile::getId)
        .filter(agentProfileId::equals)
        .isPresent();
  }

  /** True when the user holds the given capability through any of their roles. */
  private static boolean holds(User user, String code) {
    return user.getRoleAssignments().stream()
        .map(UserRole::getRole)
        .filter(Objects::nonNull)
        .flatMap(role -> role.getPermissionAssignments().stream())
        .map(RolePermission::getPermission)
        .filter(Objects::nonNull)
        .map(Permission::getCode)
        .anyMatch(code::equals);
  }

  /** The names of the roles the user holds, as a set so membership is a hash lookup. */
  private static Set<String> roleNames(User user) {
    Set<String> names = new LinkedHashSet<>();
    user.getRoleAssignments().stream()
        .map(UserRole::getRole)
        .filter(Objects::nonNull)
        .map(Role::getName)
        .filter(Objects::nonNull)
        .forEach(names::add);
    return names;
  }
}
