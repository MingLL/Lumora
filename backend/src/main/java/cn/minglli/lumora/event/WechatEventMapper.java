package cn.minglli.lumora.event;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WechatEventMapper {

    int insert(WechatEvent event);

    WechatEvent findByAppIdAndDeduplicationKey(
            @Param("appId") String appId,
            @Param("deduplicationKey") String deduplicationKey);
}
