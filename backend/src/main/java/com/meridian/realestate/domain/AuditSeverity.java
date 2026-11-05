package com.meridian.realestate.domain;

/**
 * How loudly an audit entry should be read.
 *
 * <p>Exactly the values the {@code ck_audit_severity} constraint allows. Severity is what separates
 * ordinary operational noise - a listing edited, a note added - from the entries an owner or an
 * auditor actually has to see, so the attention rail and the audit screen filter on it rather than
 * on a list of module names that changes every release.</p>
 */
public enum AuditSeverity {
  /** Ordinary activity: recorded for the trail, not for anyone's attention. */
  INFO,
  /** Something a colleague should probably notice. */
  WARNING,
  /** A security, money or data event: read it now. */
  CRITICAL;

  /** True when this entry belongs in the attention rail rather than only in the trail. */
  public boolean needsAttention() {
    return this != INFO;
  }
}
