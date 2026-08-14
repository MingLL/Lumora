package cn.minglli.lumora.event;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ClientEventMapper {
    int insert(ClientEventRecord record);
}
