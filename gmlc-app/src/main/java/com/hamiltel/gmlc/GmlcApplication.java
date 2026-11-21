package com.hamiltel.gmlc;

import com.hamiltel.gmlc.config.GmlcProperties;
import com.hamiltel.gmlc.config.OpsAlertProperties;
import com.hamiltel.gmlc.config.OpsProperties;
import com.hamiltel.gmlc.config.TrackingProperties;
import com.hamiltel.gmlc.config.WebhookProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({GmlcProperties.class, OpsProperties.class,
        OpsAlertProperties.class, WebhookProperties.class, TrackingProperties.class})
public class GmlcApplication {

    public static void main(String[] args) {
        SpringApplication.run(GmlcApplication.class, args);
    }
}
