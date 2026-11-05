package com.meridian.realestate.config;

import com.meridian.realestate.domain.Role;
import com.meridian.realestate.domain.UserRole;
import com.meridian.realestate.dto.ErrorMessages;
import com.meridian.realestate.repository.UserRepository;
import com.meridian.realestate.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authentication and the request-level authorization baseline.
 *
 * <p><b>Why this class exists.</b> It states, in one place, what an unauthenticated caller may reach
 * and how a request is turned into an authenticated one. The product is stateless: no HTTP session,
 * no CSRF token, and a bearer access token is the only credential a request carries. Only four
 * endpoints are reachable without a token - sign-in, refresh, sign-out and the readiness probe - and
 * every other request, including the rest of {@code /api/v1/auth}, must present one. Each endpoint
 * then additionally declares the permission it needs through {@code @PreAuthorize}.</p>
 *
 * <p><b>The rule it protects.</b> The filter trusts the token for <em>identity and authorities</em>
 * and for nothing else. It never reads data scope from the token:
 * {@link com.meridian.realestate.security.AccessScope} reloads the account from the database on every
 * request, so a deactivation or a permission change applies immediately rather than when a token
 * expires.</p>
 *
 * <p>Failures are written in this product's own envelope rather than the container's default body,
 * which is a sheet of HTML and is switched off in {@code application.yml}
 * ({@code server.error.include-message: never}) on purpose.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  private final JwtService jwtService;
  private final UserRepository userRepository;
  private final AppProperties properties;

  public SecurityConfig(
      JwtService jwtService, UserRepository userRepository, AppProperties properties) {
    this.jwtService = jwtService;
    this.userRepository = userRepository;
    this.properties = properties;
  }

  /** Passwords are stored as BCrypt hashes, never reversibly. */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * Resolves an account's authorities for the authentication machinery.
   *
   * <p>Authorities are the {@code ROLE_}-prefixed role names, which is the form {@code @PreAuthorize}
   * and Spring Security's own expressions expect. Permission codes are carried on the request by
   * {@link JwtAuthenticationFilter} from the token, so they are not repeated here.</p>
   *
   * <p><b>Account status is deliberately NOT enforced here.</b> Marking the account disabled at this
   * point would make the provider reject it <em>before</em> the password is checked, which tells an
   * unauthenticated caller that the address exists - the one thing the sign-in path otherwise refuses
   * to reveal. {@code AuthService} performs the password check itself and then checks the status
   * immediately afterwards, so a deactivated member of staff gets an honest "your account is not
   * active" answer while an attacker who does not know the password learns nothing.</p>
   */
  @Bean
  public UserDetailsService userDetailsService() {
    return username -> {
      String address = username == null ? "" : username.trim().toLowerCase();
      com.meridian.realestate.domain.User user =
          userRepository
              .findByEmail(address)
              .orElseThrow(() -> new UsernameNotFoundException("No account for that address"));

      List<GrantedAuthority> authorities = new ArrayList<>();
      user.getRoleAssignments().stream()
          .map(UserRole::getRole)
          .filter(Objects::nonNull)
          .map(Role::getName)
          .filter(Objects::nonNull)
          .map(name -> new SimpleGrantedAuthority("ROLE_" + name))
          .forEach(authorities::add);

      return org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
          .password(user.getPasswordHash())
          .authorities(authorities)
          .accountLocked(false)
          .accountExpired(false)
          .credentialsExpired(false)
          .disabled(false)
          .build();
    };
  }

  @Bean
  public AuthenticationProvider authenticationProvider(
      UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
    provider.setUserDetailsService(userDetailsService);
    provider.setPasswordEncoder(passwordEncoder);
    return provider;
  }

  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
      throws Exception {
    return configuration.getAuthenticationManager();
  }

  /**
   * The origins the dashboard is served from, and nothing that could be read as a secret.
   *
   * <p>Only the three headers a browser request actually needs are allowed, credentials are permitted
   * (so a cross-origin sign-in can be made), and no header is exposed on the response - there is
   * nothing in this API that a browser needs to read off a header rather than the body.</p>
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(allowedOrigins());
    configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
    configuration.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, AuthenticationProvider authenticationProvider) throws Exception {

    http.csrf(AbstractHttpConfigurer::disable)
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authenticationProvider(authenticationProvider)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh",
                        /*
                         * Sign-out is reachable without an access token on purpose, and it is the
                         * only one of the three that is a change rather than an issuance.
                         *
                         * `POST /auth/logout` carries the refresh token in its body, and that token
                         * *is* the credential being revoked: the service resolves it by hash, revokes
                         * it and audits against its own user, without reading the caller's identity
                         * from a token header. Requiring an access token as well bought nothing -
                         * anyone holding the refresh token can already do far more with
                         * `/auth/refresh`, which is public for the same reason - and it actively
                         * broke the client: the dashboard clears its local session before asking the
                         * server to revoke, so the request went out with no Authorization header,
                         * was answered 401, and left the refresh token live for the rest of its
                         * thirty days. A sign-out that silently fails to sign out is worse than one
                         * that needs no header.
                         */
                        "/api/v1/auth/logout")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/health/readiness")
                    .permitAll()
                    /*
                     * The single-page application's own files, and the shell it boots into.
                     *
                     * These have to be readable before anyone signs in, or the dashboard cannot
                     * load far enough to present a sign-in form: the shell, its hashed bundle and
                     * its icons are exactly what a first-time visitor asks for. They carry no data
                     * of the firm's - every record is fetched afterwards from the authenticated
                     * API above - so permitting them opens nothing.
                     *
                     * Note what is NOT done here: this is not written as "permit everything that
                     * is not /api/**". That inversion would look equivalent today and would quietly
                     * expose the first protected endpoint added outside /api in future. The rule
                     * below names the shell's shapes, and `anyRequest().authenticated()` still
                     * closes everything else by default.
                     */
                    .requestMatchers(
                        HttpMethod.GET,
                        "/index.html",
                        "/assets/**",
                        "/meridian-favicon.svg",
                        "/meridian-logo.svg")
                    .permitAll()
                    .requestMatchers(SecurityConfig::isSpaShellRequest)
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (request, response, authException) ->
                            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"))
                    .accessDeniedHandler(
                        (request, response, accessDeniedException) ->
                            writeError(response, HttpStatus.FORBIDDEN, "FORBIDDEN")))
        .addFilterBefore(
            new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }

  /**
   * Writes this product's error envelope, so a 401 and a 403 read the same as every other failure.
   *
   * <p>The sentence comes from {@link ErrorMessages} so the wording matches the body the controllers
   * produce for the same code; only the constant code and that sentence are written, so no internal
   * detail reaches the caller.</p>
   */
  private static void writeError(HttpServletResponse response, HttpStatus status, String code)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write(
            "{\"success\":false,\"error\":{\"code\":\""
                + code
                + "\",\"message\":\""
                + ErrorMessages.friendly(code)
                + "\",\"details\":[]}}");
  }

  /** The configured origins, split on commas and trimmed; an unset value allows none. */
  private List<String> allowedOrigins() {
    String raw = properties.getCors() == null ? null : properties.getCors().getAllowedOrigins();
    if (raw == null || raw.isBlank()) return List.of();
    return Arrays.stream(raw.split(",")).map(String::trim).filter(origin -> !origin.isEmpty()).toList();
  }

  /**
   * True for a request that is the SPA asking for a client-routed page rather than for the API.
   *
   * <p>Written as a predicate rather than a pattern because the exclusion that matters - "and not
   * {@code /api/}" - is a negative one, which an Ant or PathPattern cannot express. The three
   * conditions are each load-bearing:</p>
   *
   * <ul>
   *   <li><b>GET only.</b> The shell is read, never written. A POST to an extension-less path is not
   *       a page request and must stay closed.</li>
   *   <li><b>Not {@code /api/}.</b> Keeping this check first is what preserves the contract that
   *       every non-public API path answers 401 without a token, including paths that do not exist -
   *       so an unauthenticated caller learns nothing about which endpoints are real.</li>
   *   <li><b>No extension in the last segment.</b> {@code /properties/3f1c…} is a page;
   *       {@code /assets/index-CuIT9Ss7.js} is a file, and is already matched explicitly above.
   *       Without this, a missing asset would be answered with the HTML shell and cached as a
   *       successful script request.</li>
   * </ul>
   *
   * <p>Being generous here is safe: this only lets a request through the filter chain. Whether a
   * page is actually served is {@link WebConfig}'s fallback decision, and a path it declines still
   * ends as the product's JSON 404 envelope.</p>
   */
  private static boolean isSpaShellRequest(HttpServletRequest request) {
    if (!HttpMethod.GET.matches(request.getMethod())) return false;

    String path = request.getRequestURI();
    if (path.startsWith("/api/")) return false;

    return path.indexOf('.', path.lastIndexOf('/') + 1) < 0;
  }

  /**
   * Reads the bearer token and populates the security context.
   *
   * <p>The principal is set to the account id as a {@code String} - that is what
   * {@link com.meridian.realestate.security.AccessScope} reads and reloads against - and the
   * authorities are the {@code ROLE_}-prefixed role names plus the raw permission codes.</p>
   *
   * <p>An invalid, tampered or expired token is <b>ignored rather than thrown</b>: the request simply
   * continues unauthenticated and the entry point answers 401. That keeps a single response shape for
   * "no token" and "bad token", so the API never tells a caller which of the two it was.</p>
   */
  public static class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
      this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

      String header = request.getHeader("Authorization");
      if (header != null && header.startsWith(BEARER_PREFIX)) {
        String token = header.substring(BEARER_PREFIX.length()).trim();
        try {
          if (!token.isEmpty() && jwtService.isValid(token)) {
            Claims claims = jwtService.parseClaims(token);
            String subject = claims.getSubject();
            if (subject != null && !subject.isBlank()) {
              var authentication =
                  new UsernamePasswordAuthenticationToken(
                      subject, null, extractAuthorities(claims));
              SecurityContextHolder.getContext().setAuthentication(authentication);
            }
          }
        } catch (Exception ignored) {
          // Left unauthenticated on purpose; the entry point returns 401.
        }
      }

      filterChain.doFilter(request, response);
    }

    /** Role names as {@code ROLE_<name>}, and permission codes exactly as they are. */
    private static List<GrantedAuthority> extractAuthorities(Claims claims) {
      List<GrantedAuthority> authorities = new ArrayList<>();

      Object roles = claims.get("roles");
      if (roles instanceof Collection<?> roleNames) {
        roleNames.stream()
            .map(String::valueOf)
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
            .forEach(authorities::add);
      }

      Object permissions = claims.get("permissions");
      if (permissions instanceof Collection<?> codes) {
        codes.stream().map(String::valueOf).map(SimpleGrantedAuthority::new).forEach(authorities::add);
      }

      return authorities;
    }
  }
}
