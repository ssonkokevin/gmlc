package com.hamiltel.gmlc.ss7;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the MAP transport. With no STP or no local point code the transport
 * refuses every exchange, so an unconfigured deployment reports
 * SIGNALLING_NOT_CONFIGURED instead of pretending it can reach an HLR.
 */
@Configuration
@EnableConfigurationProperties(M3uaProperties.class)
public class M3uaConfiguration {

    @Bean(destroyMethod = "close")
    public M3uaTransport m3uaTransport(M3uaProperties properties) {
        if (!properties.configured()) {
            return M3uaTransport.disabled(
                    "no M3UA signalling gateway is configured; set GMLC_M3UA_HOST and GMLC_M3UA_LOCAL_PC");
        }
        return new TcpM3uaTransport(properties.host(), properties.port(),
                properties.localPointCode(), properties.networkIndicator(),
                properties.routingContextOrNull());
    }
}
