package com.meridian.realestate.domain;

/**
 * The file format an import was staged from, exactly as {@code ck_import_jobs_source} enumerates it.
 *
 * <p>The format is recorded because it explains the parse: a spreadsheet carries typed cells and a
 * row/column grid, a CSV carries text and nothing else, so the same value can be read differently
 * depending on where it came from. The file itself is never kept, so this column is the only
 * surviving description of the input.</p>
 */
public enum ImportSourceType {
  /** A delimited text file, RFC 4180. */
  CSV,
  /** A modern binary spreadsheet. */
  XLSX,
  /** The legacy binary spreadsheet format. */
  XLS
}
