package com.hamiltel.gmlc.diameter;

import com.hamiltel.gmlc.ops.MonitoredDiameterTransport;
import com.hamiltel.gmlc.ops.PeerHealth;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Diameter clients. With no peer configured the transport refuses
 * every exchange, so a deployment that has not been pointed at a DRA behaves
 * exactly as before: the request path fails closed instead of pretending.
 */
@Configuration
@EnableConfigurationProperties(DiameterProperties.class)
public class DiameterConfiguration {

    @Bean(destroyMethod = "close")
    public DiameterTransport diameterTransport(DiameterProperties properties,
                                               PeerHealth peerHealth) {
        if (!properties.configured()) {
            return DiameterTransport.disabled(
                    "no Diameter peer is configured; set GMLC_DIAMETER_PEER_HOST");
        }
        return new MonitoredDiameterTransport(
                new TcpDiameterTransport(properties.peerHost(), properties.peerPort(),
                        properties.originHost(), properties.originRealm(), "GMLC"),
                peerHealth);
    }

    @Bean
    public SlhClient slhClient(DiameterTransport transport, DiameterProperties properties) {
        return new SlhClient(transport, properties);
    }

    @Bean
    public SlgClient slgClient(DiameterTransport transport, DiameterProperties properties) {
        return new SlgClient(transport, properties);
    }
}
