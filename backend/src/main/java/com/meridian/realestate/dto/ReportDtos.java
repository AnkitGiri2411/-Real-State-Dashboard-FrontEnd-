package com.meridian.realestate.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * The shape every report returns, so the grid, the totals row and the CSV export all agree.
 *
 * <p><b>Why one shape.</b> Twelve reports that each invented their own JSON would need twelve CSV
 * writers and twelve sort implementations, and the twelfth would eventually disagree with the
 * others about how a currency or a blank is rendered. A report is a headed table of typed cells with
 * a totals row, so it is modelled as one: {@link ReportTable} names its columns (so a CSV can head
 * itself without being told what the report is), carries the rows, and states the totals
 * separately.</p>
 *
 * <p><b>Every figure is real.</b> A report is built by querying the operational tables for the
 * requested window; no report contains a hardcoded number, and a figure that cannot be computed
 * honestly from the data is not returned rather than guessed.</p>
 */
public final class ReportDtos {

  private ReportDtos() {}

  /** The value shape a column carries, so the client can right-align money and dates correctly. */
  public enum ColumnType {
    TEXT,
    INTEGER,
    DECIMAL,
    MONEY,
    DATE,
    DATETIME
  }

  /** One column of a report: a stable key, the heading a person reads, and the value shape. */
  public record ReportColumn(String key, String label, ColumnType type) {}

  /**
   * A finished report.
   *
   * @param key the stable report identifier, matching its route
   * @param title the heading, as it will be printed
   * @param from the inclusive start of the window the figures cover; null when the report is not
   *     windowed
   * @param to the inclusive end of the window
   * @param generatedAt when the report was produced
   * @param columns the columns, in the order they print
   * @param rows the rows, each a map from a column key to its value
   * @param totals the totals row, keyed by column key; empty when a total would be meaningless
   */
  public record ReportTable(
      String key,
      String title,
      LocalDate from,
      LocalDate to,
      LocalDateTime generatedAt,
      List<ReportColumn> columns,
      List<Map<String, Object>> rows,
      Map<String, Object> totals) {}
}
