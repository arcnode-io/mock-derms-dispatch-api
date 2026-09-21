package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IhlfCsvParserTest {

  // Reason: real header + row shape, verified against the live NP3-562-CD report (two Model runs
  // per interval, one InUseFlag=Y) rather than assumed.
  private static final String REAL_SHAPE_CSV =
      """
      IntervalEnding,Coast,East,FarWest,North,NorthCentral,SouthCentral,Southern,West,SystemTotal,Model,InUseFlag,DSTFlag
      09/21/2026 08:25,12345.6,2345.6,3456.7,1714.08,8765.4,5432.1,2109.8,3210.9,60439.8,STLF,N,N
      09/21/2026 08:25,12300.1,2300.1,3400.2,1710.55,8700.2,5400.5,2100.2,3200.1,60300.1,MTLF,Y,N
      09/21/2026 08:30,12400.0,2400.0,3500.0,1726.62,8800.0,5450.0,2150.0,3250.0,60748.0,MTLF,Y,N
      """;

  @Test
  void parsesNorthZoneMwFromEarliestInUseFlagYRow() {
    double result = IhlfCsvParser.parseNorthZoneMw(REAL_SHAPE_CSV);

    assertThat(result).isEqualTo(1710.55);
  }

  @Test
  void rejectsCsvWithNoInUseFlagYRow() {
    String csv =
        """
        IntervalEnding,North,InUseFlag
        09/21/2026 08:25,1714.08,N
        """;

    assertThatThrownBy(() -> IhlfCsvParser.parseNorthZoneMw(csv))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("InUseFlag=Y");
  }
}
