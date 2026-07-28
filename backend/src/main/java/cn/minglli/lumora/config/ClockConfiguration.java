package cn.minglli.lumora.config;

import java.time.Clock;

import cn.minglli.lumora.report.ReportDeliveryService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfiguration {

    @Bean
    Clock clock(LumoraProperties properties) {
        return Clock.system(properties.getZone());
    }

    @Bean
    ReportDeliveryService.Sleeper mailSleeper() {
        return millis -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
