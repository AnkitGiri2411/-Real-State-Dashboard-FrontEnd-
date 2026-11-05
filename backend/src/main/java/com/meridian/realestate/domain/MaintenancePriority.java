package com.meridian.realestate.domain;

/**
 * How quickly a maintenance request must be dealt with.
 *
 * <p>Exactly the values the {@code ck_maintenance_requests_priority} constraint allows.</p>
 *
 * <p>Priority is not decoration: {@code URGENT} and {@code HIGH} are the two values the dashboard's
 * urgent-work count reads, over the requests still in the open set. That count is the number on the
 * operations card, so the values here are the exact set the KPI is drawn from.</p>
 */
public enum MaintenancePriority {
  LOW,
  MEDIUM,
  HIGH,
  URGENT;

  /** True when this priority counts towards the urgent-work KPI. */
  public boolean isUrgent() {
    return this == HIGH || this == URGENT;
  }
}
