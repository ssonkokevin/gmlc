package com.hamiltel.gmlc.location;

import com.hamiltel.gmlc.cell.CellSelector;
import com.hamiltel.gmlc.diameter.SlgClient;
import com.hamiltel.gmlc.diameter.SlhClient;
import com.hamiltel.gmlc.map.MapClient;
import com.hamiltel.gmlc.map.MapProperties;
import com.hamiltel.gmlc.ss7.M3uaProperties;
import com.hamiltel.gmlc.ss7.M3uaTransport;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One locator bean, selected by {@code gmlc.location.transport}. Building only
 * the chosen locator keeps a single {@link LocationLocator} available for
 * injection and makes the signalling face an explicit deployment decision
 * rather than a fallback race between two clients.
 */
@Configuration
@EnableConfigurationProperties({LocationProperties.class, MapProperties.class})
public class LocationConfiguration {

    @Bean
    public MapClient mapClient(M3uaTransport transport, MapProperties map, M3uaProperties m3ua) {
        return new MapClient(transport, map, m3ua);
    }

    @Bean
    public LocationLocator locationLocator(LocationProperties location, SlhClient slh,
                                           SlgClient slg, MapClient mapClient,
                                           CellSelector cells) {
        return location.map()
                ? new MapCellLocator(mapClient, cells)
                : new DiameterCellLocator(slh, slg, cells);
    }
}
