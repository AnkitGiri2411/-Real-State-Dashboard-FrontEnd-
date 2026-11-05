package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.AuthDtos;
import com.meridian.realestate.security.AccessScope;
import com.meridian.realestate.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The identity endpoints.
 *
 * <p>{@code POST /login} and {@code POST /refresh} are the only endpoints here reachable without a
 * token (see {@code SecurityConfig}); sign-out, {@code /me} and the password change all require one.
 * Every success is wrapped in {@link ApiResponse}, so the client unwraps one shape and never has to
 * branch on the address it called.</p>
 *
 * <p>The client address is taken from the first hop of {@code X-Forwarded-For} when a proxy set it,
 * and from the socket otherwise, and is recorded against the session it opens - a fact about the
 * network, never a fact about a person.</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

  private final AuthService authService;
  private final AccessScope accessScope;

  public AuthController(AuthService authService, AccessScope accessScope) {
    this.authService = authService;
    this.accessScope = accessScope;
  }

  @PostMapping("/login")
  public ApiResponse<AuthDtos.AuthTokens> login(
      @Valid @RequestBody AuthDtos.LoginRequest request, HttpServletRequest httpRequest) {
    return ApiResponse.ok(
        authService.login(
            request.email(), request.password(), clientIp(httpRequest), userAgent(httpRequest)));
  }

  @PostMapping("/refresh")
  public ApiResponse<AuthDtos.AuthTokens> refresh(
      @Valid @RequestBody AuthDtos.RefreshRequest request, HttpServletRequest httpRequest) {
    return ApiResponse.ok(
        authService.refresh(request.refreshToken(), clientIp(httpRequest), userAgent(httpRequest)));
  }

  @PostMapping("/logout")
  public ApiResponse<AuthDtos.MessageResponse> logout(
      @Valid @RequestBody AuthDtos.RefreshRequest request) {
    authService.logout(request.refreshToken());
    return ApiResponse.ok(new AuthDtos.MessageResponse("You have been signed out"));
  }

  @PostMapping("/logout-all")
  public ApiResponse<AuthDtos.MessageResponse> logoutAll() {
    authService.logoutEverywhere(accessScope.requireUserId());
    return ApiResponse.ok(new AuthDtos.MessageResponse("You have been signed out everywhere"));
  }

  @GetMapping("/me")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<AuthDtos.CurrentUserResponse> me() {
    return ApiResponse.ok(authService.currentUser());
  }

  @PostMapping("/change-password")
  @PreAuthorize("isAuthenticated()")
  public ApiResponse<AuthDtos.MessageResponse> changePassword(
      @Valid @RequestBody AuthDtos.ChangePasswordRequest request) {
    authService.changePassword(
        request.currentPassword(), request.newPassword(), request.confirmPassword());
    return ApiResponse.ok(new AuthDtos.MessageResponse("Your password has been changed"));
  }

  /**
   * The client address, from the first {@code X-Forwarded-For} hop when a proxy set one.
   *
   * <p>Only the first hop is meaningful: the rest were appended by whatever the client sent, and a
   * header a client controls is not evidence of anything.</p>
   */
  private static String clientIp(HttpServletRequest request) {
    if (request == null) return null;
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int comma = forwarded.indexOf(',');
      return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
    }
    return request.getRemoteAddr();
  }

  private static String userAgent(HttpServletRequest request) {
    return request == null ? null : request.getHeader("User-Agent");
  }
}
