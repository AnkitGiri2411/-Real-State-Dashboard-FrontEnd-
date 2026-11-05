package com.meridian.realestate.controller;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The readiness probe.
 *
 * <p><b>Why this class exists.</b> {@code GET /api/v1/health/readiness} is one of the few addresses
 * reachable without a token (see {@code SecurityConfig}), because a load balancer, a container
 * orchestrator or an uptime monitor has no account. It therefore answers the smallest useful question
 * - is this instance up, under which environment label - and says nothing that could not be
 * published.</p>
 *
 * <p><b>The rule it protects.</b> The body carries no secret and reaches no dependency: no password,
 * no connection string, no signing key, no library version to fingerprint, and no database round trip
 * that could make the probe slow or make a healthy instance look unready when a shared database is
 * busy. It reports only what the instance knows about itself. Its answer shape is fixed and flat so a
 * probe can read {@code status} directly; the label {@code env} is the configured
 * {@code app.env}, nothing more.</p>
 *
 * <p>{@code dataSource} is the one addition, and it stays inside that rule: it names the configured
 * mode ({@code postgres} or {@code sql-file}) and not an address, a host, a database name or a
 * credential. It is here because it is otherwise invisible from outside - an instance reading the SQL
 * files and one reading a managed database answer identically, and an operator who has just switched
 * modes needs one command to confirm which they are talking to.</p>
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

  private final String appName;
  private final String appEnv;
  private final String dataSource;

  public HealthController(
      @Value("${spring.application.name:real-estate-backend}") String appName,
      @Value("${app.env:development}") String appEnv,
      @Value("${app.data-source:postgres}") String dataSource) {
    this.appName = appName;
    this.appEnv = appEnv;
    // Reported as the product's own wording rather than as the configuration value, so the probe
    // does not become a place where a config key is spelled for the reader to copy.
    this.dataSource = "sqlfiles".equalsIgnoreCase(dataSource.trim()) ? "sql-file" : "postgres";
  }

  /** The readiness body: {@code status}, the application name, the environment and the instant. */
  @GetMapping("/readiness")
  public Map<String, Object> readiness() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", "UP");
    body.put("app", appName);
    body.put("env", appEnv);
    body.put("dataSource", dataSource);
    body.put("time", OffsetDateTime.now().toString());
    return body;
  }
}
