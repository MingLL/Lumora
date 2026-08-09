package cn.minglli.lumora.wechat;

import java.time.Instant;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface JsapiSignatureErrorMapper {

    int insert(JsapiSignatureErrorRecord record);

    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
