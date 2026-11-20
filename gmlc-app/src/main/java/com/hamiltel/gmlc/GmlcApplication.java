package com.hamiltel.gmlc;

import com.hamiltel.gmlc.config.GmlcProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GmlcProperties.class)
public class GmlcApplication {

    public static void main(String[] args) {
        SpringApplication.run(GmlcApplication.class, args);
    }
}
