package com.meridian.realestate.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.meridian.realestate.domain.AgentProfile;
import com.meridian.realestate.domain.Permission;
import com.meridian.realestate.domain.Role;
import com.meridian.realestate.domain.RolePermission;
import com.meridian.realestate.domain.User;
import com.meridian.realestate.domain.UserRole;
import com.meridian.realestate.exception.ApiException;
import com.meridian.realestate.repository.AgentProfileRepository;
import com.meridian.realestate.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Whose records the caller may see, with the repository mocked and no database.
 *
 * <p>What is asserted here is the scope decision and the two refusals that hang off it: an
 * unauthenticated caller has no scope at all, a firm-wide role sees the whole firm, and a scoped
 * agent role does not - while still holding every capability the work they do needs, which is why the
 * scope check and the permission check are separate assertions on the same account.</p>
 *
 * <p>The account is not rebuilt between the calls a single test makes, so the mock stands in for the
 * per-call reload: the point of that reload is proved by the code, not by this test, but the test
 * pins the two decisions the reload feeds.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessScope: whose records the caller may see")
class AccessScopeTest {

  @Mock private UserRepository userRepository;
  @Mock private AgentProfileRepository agentProfiles;

  private AccessScope scope;

  @BeforeEach
  void setUp() {
    scope = new AccessScope(userRepository, agentProfiles);
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private static void signIn(UUID id) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
  }

  private static Permission permission(String code) {
    Permission permission = new Permission();
    permission.setCode(code);
    return permission;
  }

  /** An account holding one role, optionally with the given permission codes. */
  private static User account(UUID id, String roleName, String... permissionCodes) {
    Role role = new Role();
    role.setName(roleName);
    for (String code : permissionCodes) {
      role.addPermissionAssignment(new RolePermission(role, permission(code)));
    }

    User user = new User();
    user.setId(id);
    user.setEmail("staff@meridianestates.example");
    user.setFirstName("Test");
    user.setLastName("Account");
    user.addRoleAssignment(new UserRole(user, role));
    return user;
  }

  @Test
  @DisplayName("with no authentication there is no current user, and requireUser refuses")
  void noAuthenticationMeansNoUser() {
    assertThat(scope.currentUser()).isEmpty();
    assertThatThrownBy(scope::requireUser)
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).getStatus())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("a principal that is not an account id is treated as unauthenticated")
  void aNonUuidPrincipalIsUnauthenticated() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of()));

    assertThat(scope.currentUser()).isEmpty();
  }

  @Test
  @DisplayName("a firm-wide role sees the whole portfolio")
  void aFirmWideRoleIsCompanyWide() {
    UUID id = UUID.randomUUID();
    signIn(id);
    when(userRepository.findByIdAndDeletedAtIsNull(id))
        .thenReturn(Optional.of(account(id, "PROPERTY_MANAGER")));

    assertThat(scope.isCompanyWide()).isTrue();
  }

  @Test
  @DisplayName("a scoped agent role is not firm-wide")
  void aScopedAgentRoleIsNotCompanyWide() {
    UUID id = UUID.randomUUID();
    signIn(id);
    when(userRepository.findByIdAndDeletedAtIsNull(id))
        .thenReturn(Optional.of(account(id, "LEASING_AGENT")));

    assertThat(scope.isCompanyWide()).isFalse();
  }

  @Test
  @DisplayName("hasPermission is true only for a code the account actually holds")
  void hasPermissionIsTrueOnlyForAHeldCode() {
    UUID id = UUID.randomUUID();
    signIn(id);
    when(userRepository.findByIdAndDeletedAtIsNull(id))
        .thenReturn(Optional.of(account(id, "LEASING_AGENT", "LEAD_VIEW")));

    assertThat(scope.hasPermission("LEAD_VIEW")).isTrue();
    assertThat(scope.hasPermission("USER_DEACTIVATE")).isFalse();
    assertThat(scope.hasPermission(null)).isFalse();
  }

  @Test
  @DisplayName("hasAnyPermission matches when any one of the codes is held")
  void hasAnyPermissionMatchesOneOfSeveral() {
    UUID id = UUID.randomUUID();
    signIn(id);
    when(userRepository.findByIdAndDeletedAtIsNull(id))
        .thenReturn(Optional.of(account(id, "ACCOUNTING", "PAYMENT_RECORD")));

    assertThat(scope.hasAnyPermission("USER_DEACTIVATE", "PAYMENT_RECORD")).isTrue();
    assertThat(scope.hasAnyPermission("USER_DEACTIVATE", "ROLE_UPDATE")).isFalse();
  }

  @Test
  @DisplayName("requirePermission refuses a missing capability with a 403")
  void requirePermissionRefusesAMissingCapability() {
    UUID id = UUID.randomUUID();
    signIn(id);
    when(userRepository.findByIdAndDeletedAtIsNull(id))
        .thenReturn(Optional.of(account(id, "VIEWER")));

    assertThatThrownBy(() -> scope.requirePermission("USER_DEACTIVATE"))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).getStatus())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("requireCompanyWide refuses a scoped caller with a 403")
  void requireCompanyWideRefusesAScopedCaller() {
    UUID id = UUID.randomUUID();
    signIn(id);
    when(userRepository.findByIdAndDeletedAtIsNull(id))
        .thenReturn(Optional.of(account(id, "SALES_AGENT")));

    assertThatThrownBy(() -> scope.requireCompanyWide("see the whole portfolio"))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).getStatus())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("a scoped agent may see only the profile tied to their own account")
  void aScopedAgentSeesOnlyTheirOwnProfile() {
    UUID id = UUID.randomUUID();
    UUID ownProfile = UUID.randomUUID();
    signIn(id);
    when(userRepository.findByIdAndDeletedAtIsNull(id))
        .thenReturn(Optional.of(account(id, "LEASING_AGENT")));

    AgentProfile profile = new AgentProfile();
    profile.setId(ownProfile);
    when(agentProfiles.findByUserIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(profile));

    assertThat(scope.canSeeAgentWork(ownProfile)).isTrue();
    assertThat(scope.canSeeAgentWork(UUID.randomUUID())).isFalse();
    assertThat(scope.canSeeAgentWork(null)).isFalse();
  }

  @Test
  @DisplayName("a firm-wide caller may see any agent's work")
  void aFirmWideCallerSeesEveryAgent() {
    UUID id = UUID.randomUUID();
    signIn(id);
    when(userRepository.findByIdAndDeletedAtIsNull(id))
        .thenReturn(Optional.of(account(id, "PROPERTY_MANAGER")));

    assertThat(scope.canSeeAgentWork(UUID.randomUUID())).isTrue();
  }
}
