package com.meridian.realestate.controller;

import com.meridian.realestate.dto.ApiResponse;
import com.meridian.realestate.dto.ReportDtos;
import com.meridian.realestate.service.ReportService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports.
 *
 * <p>Twelve reports, produced by real queries and returned in one shape ({@link
 * ReportDtos.ReportTable}), so the grid, the totals row and the CSV export never disagree. The
 * controller routes a report key to the service and writes the CSV to the response stream: there is
 * no temporary file, and the download is generated from the same {@code ReportTable} the JSON
 * endpoint returns.</p>
 *
 * <p>Guarded by {@code REPORT_VIEW}, which the seeded {@code V2} matrix gives to every role that is
 * meant to see the portfolio's figures.</p>
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

  private final ReportService reportService;

  public ReportController(ReportService reportService) {
    this.reportService = reportService;
  }

  /** The report keys this installation serves, so a menu offers only real ones. */
  @GetMapping
  @PreAuthorize("hasAuthority('REPORT_VIEW')")
  public ApiResponse<List<String>> catalogue() {
    return ApiResponse.ok(ReportService.KEYS);
  }

  /** One report as JSON. */
  @GetMapping("/{report}")
  @PreAuthorize("hasAuthority('REPORT_VIEW')")
  public ApiResponse<ReportDtos.ReportTable> report(
      @PathVariable String report,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(required = false) String sort) {
    return ApiResponse.ok(reportService.run(report, from, to, propertyId, sort));
  }

  /**
   * The same report as a CSV download.
   *
   * <p>Written directly to the response stream with commons-csv, which quotes a value that contains
   * the delimiter or a newline and so produces a file a spreadsheet opens without the columns
   * drifting. Nothing is buffered to disk.</p>
   */
  @GetMapping(value = "/{report}/export", produces = "text/csv")
  @PreAuthorize("hasAuthority('REPORT_VIEW')")
  public void export(
      @PathVariable String report,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(required = false) String sort,
      HttpServletResponse response)
      throws IOException {

    ReportDtos.ReportTable table = reportService.run(report, from, to, propertyId, sort);

    response.setContentType("text/csv");
    response.setCharacterEncoding("UTF-8");
    response.setHeader(
        "Content-Disposition", "attachment; filename=\"" + table.key() + ".csv\"");

    PrintWriter writer = response.getWriter();
    try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
      printer.printRecord(table.columns().stream().map(ReportDtos.ReportColumn::label).toList());
      for (Map<String, Object> row : table.rows()) {
        printer.printRecord(
            table.columns().stream().map(column -> format(row.get(column.key()))).toList());
      }
      if (!table.totals().isEmpty()) {
        printer.printRecord(
            table.columns().stream()
                .map(
                    column ->
                        table.totals().containsKey(column.key())
                            ? format(table.totals().get(column.key()))
                            : "")
                .toList());
      }
      printer.flush();
    }
  }

  private static String format(Object value) {
    if (value == null) return "";
    if (value instanceof BigDecimal amount) return amount.toPlainString();
    return String.valueOf(value);
  }
}
