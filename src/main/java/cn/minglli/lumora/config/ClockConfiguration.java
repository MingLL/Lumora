package cn.minglli.lumora.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfiguration {

    @Bean
    Clock clock(LumoraProperties properties) {
        return Clock.system(properties.getZone());
    }
}
