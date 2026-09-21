package io.arcnode.mockderms.dispatch;

/**
 * Parses ERCOT NP3-562-CD ("Intra-Hour Load Forecast by Weather Zone") CSV rows — verified against
 * the real live report: header {@code IntervalEnding,Coast,East,FarWest,North,NorthCentral,
 * SouthCentral,Southern,West,SystemTotal,Model,InUseFlag,DSTFlag}, rows in ascending IntervalEnding
 * order, multiple {@code Model} rows per interval but only one {@code InUseFlag=Y} (the
 * authoritative forecast run). The first {@code InUseFlag=Y} row is therefore the earliest
 * interval's authoritative value — the closest available proxy for "current" North-zone load.
 */
final class IhlfCsvParser {

  private static final String INTERVAL_ENDING_COLUMN = "IntervalEnding";
  private static final String NORTH_COLUMN = "North";
  private static final String IN_USE_FLAG_COLUMN = "InUseFlag";
  private static final String IN_USE = "Y";

  private IhlfCsvParser() {}

  /** North-zone load in MW from the earliest {@code InUseFlag=Y} row. */
  static double parseNorthZoneMw(String csv) {
    String[] lines = csv.split("\r?\n");
    if (lines.length == 0) {
      throw new IllegalStateException("IHLF CSV was empty");
    }
    String[] header = lines[0].split(",");
    int northIdx = columnIndex(header, NORTH_COLUMN);
    int inUseIdx = columnIndex(header, IN_USE_FLAG_COLUMN);
    columnIndex(header, INTERVAL_ENDING_COLUMN); // Reason: fail fast if the report shape changes.

    for (int i = 1; i < lines.length; i++) {
      if (lines[i].isBlank()) {
        continue;
      }
      String[] row = lines[i].split(",");
      if (IN_USE.equals(row[inUseIdx])) {
        return Double.parseDouble(row[northIdx]);
      }
    }
    throw new IllegalStateException("no InUseFlag=Y row found in IHLF CSV");
  }

  private static int columnIndex(String[] header, String name) {
    for (int i = 0; i < header.length; i++) {
      if (header[i].equals(name)) {
        return i;
      }
    }
    throw new IllegalStateException("IHLF CSV missing expected column: " + name);
  }
}
