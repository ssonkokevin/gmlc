package com.hamiltel.gmlc.tracking;

import com.hamiltel.gmlc.config.TrackingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Ticks the automated tracking schedules. The work itself is in the service so
 * a test can advance the clock and call {@link TrackingScheduleService#dispatchDue}
 * without waiting for the timer.
 */
@Component
public class TrackingScheduleRunner {

    private static final Logger log = LoggerFactory.getLogger(TrackingScheduleRunner.class);

    private final TrackingScheduleService schedules;
    private final TrackingProperties properties;

    public TrackingScheduleRunner(TrackingScheduleService schedules, TrackingProperties properties) {
        this.schedules = schedules;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${gmlc.tracking.flush-seconds:30}",
            initialDelayString = "${gmlc.tracking.initial-delay-seconds:45}",
            timeUnit = TimeUnit.SECONDS)
    public void tick() {
        if (!properties.schedulesEnabled()) {
            return;
        }
        try {
            schedules.dispatchDue(Instant.now());
        } catch (RuntimeException e) {
            // The rows stay due; the next tick is the retry.
            log.warn("tracking schedule tick skipped: {}", e.getClass().getSimpleName());
        }
    }
}
