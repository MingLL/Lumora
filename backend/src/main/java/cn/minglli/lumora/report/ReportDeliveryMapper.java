package cn.minglli.lumora.report;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ReportDeliveryMapper {

    int upsertAuto(@Param("deliveryId") String deliveryId, @Param("reportId") Long reportId);

    ReportDeliveryRecord findAutoByReportId(@Param("reportId") Long reportId);

    ReportDeliveryRecord findByDeliveryId(@Param("deliveryId") String deliveryId);

    ReportDeliveryRecord findByReportIdAndRequestId(
            @Param("reportId") Long reportId, @Param("requestId") String requestId);

    int claim(@Param("id") Long id, @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);

    int insertManual(
            @Param("deliveryId") String deliveryId,
            @Param("reportId") Long reportId,
            @Param("requestId") String requestId,
            @Param("recipientMasked") String recipientMasked,
            @Param("recipientSha256") String recipientSha256);

    int markSent(@Param("id") Long id, @Param("now") Instant now);

    int markFailed(@Param("id") Long id, @Param("now") Instant now,
                   @Param("errorClass") String errorClass, @Param("errorSummary") String errorSummary);

    int markPendingRetry(@Param("id") Long id, @Param("now") Instant now,
                         @Param("errorClass") String errorClass, @Param("errorSummary") String errorSummary);

    int reclaimStale(@Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);

    List<ReportDeliveryRecord> findRecoverable(@Param("now") Instant now);

    List<ReportDeliveryRecord> findActiveByReportId(@Param("reportId") Long reportId);

    List<ReportDeliveryRecord> findSentByReportId(@Param("reportId") Long reportId);
}
