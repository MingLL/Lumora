package cn.minglli.lumora.event;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
public class WechatEventRepository {

    private final WechatEventMapper mapper;

    public WechatEventRepository(WechatEventMapper mapper) {
        this.mapper = mapper;
    }

    public InsertResult insert(WechatEvent event) {
        try {
            mapper.insert(event);
            return InsertResult.INSERTED;
        } catch (DuplicateKeyException exception) {
            return InsertResult.DUPLICATE;
        }
    }

    public Optional<WechatEvent> findByAppIdAndDeduplicationKey(
            String appId, String deduplicationKey) {
        return Optional.ofNullable(
                mapper.findByAppIdAndDeduplicationKey(appId, deduplicationKey));
    }

    public enum InsertResult {
        INSERTED,
        DUPLICATE
    }
}
