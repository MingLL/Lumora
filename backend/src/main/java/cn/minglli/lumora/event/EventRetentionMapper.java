package cn.minglli.lumora.event;

import java.time.Instant;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface EventRetentionMapper {

    int nullCoordinatesOlderThan(@Param("cutoff") Instant cutoff);

    int deleteDeliveriesOlderThan(@Param("cutoff") Instant cutoff);

    int deleteUnreferencedReportsOlderThan(@Param("cutoff") Instant cutoff);

    int deleteEventsOlderThan(@Param("cutoff") Instant cutoff);

    int deleteClientEventsOlderThan(@Param("cutoff") Instant cutoff);

    int deleteJsapiSignatureErrorsOlderThan(@Param("cutoff") Instant cutoff);
}
