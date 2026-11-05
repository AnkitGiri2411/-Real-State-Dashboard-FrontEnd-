package com.meridian.realestate.domain;

/**
 * How loudly a notification should read, exactly as {@code ck_notifications_severity} enumerates it.
 *
 * <p>Severity drives the appearance of the row in the bell, not its visibility. It is a closed set
 * because an invented level would render as an unstyled row, which is a state nobody designed.</p>
 *
 * <p>{@code notifications.type} deliberately has no enum: the column carries no CHECK constraint,
 * so it is a free VARCHAR(60) whose vocabulary is owned by whoever raises the notification, and
 * inventing a Java enum for it would claim a closed set the database does not enforce.</p>
 */
public enum NotificationSeverity {
  /** For information only. */
  INFO,
  /** Something to act on before it becomes a problem. */
  WARNING,
  /** Something already wrong. */
  CRITICAL
}
