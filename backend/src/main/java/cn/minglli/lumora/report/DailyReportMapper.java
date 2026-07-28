package cn.minglli.lumora.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DailyReportMapper {

    long countTotalEvents(@Param("s") Instant s, @Param("e") Instant e, @Param("cutoff") Instant cutoff);

    long countTotalUniqueUsers(@Param("s") Instant s, @Param("e") Instant e, @Param("cutoff") Instant cutoff);

    List<DailyReportSnapshot.EventTypeCount> countByEventType(
            @Param("s") Instant s, @Param("e") Instant e, @Param("cutoff") Instant cutoff);

    List<DailyReportSnapshot.LabelCount> countQrScenes(
            @Param("s") Instant s, @Param("e") Instant e, @Param("cutoff") Instant cutoff);

    List<DailyReportSnapshot.LabelCount> countMenuClickKeys(
            @Param("s") Instant s, @Param("e") Instant e, @Param("cutoff") Instant cutoff);

    List<DailyReportSnapshot.LabelCount> countMenuViewUrls(
            @Param("s") Instant s, @Param("e") Instant e, @Param("cutoff") Instant cutoff);

    List<DailyReportSnapshot.MenuOtherCount> countMenuOther(
            @Param("s") Instant s, @Param("e") Instant e, @Param("cutoff") Instant cutoff);

    long countLocationReports(@Param("s") Instant s, @Param("e") Instant e, @Param("cutoff") Instant cutoff);

    long countLocationUniqueUsers(@Param("s") Instant s, @Param("e") Instant e, @Param("cutoff") Instant cutoff);

    long countAnomalousTimestamps(@Param("s") Instant s, @Param("e") Instant e, @Param("cutoff") Instant cutoff);

    int insertSnapshot(DailyReportRecord record);

    DailyReportRecord findByDateAndVersion(@Param("reportDate") LocalDate reportDate, @Param("version") int version);

    DailyReportRecord findDailyReportById(@Param("id") Long id);

    DailyReportRecord findLatestVersion(@Param("reportDate") LocalDate reportDate);

    Integer findMaxVersion(@Param("reportDate") LocalDate reportDate);
}
