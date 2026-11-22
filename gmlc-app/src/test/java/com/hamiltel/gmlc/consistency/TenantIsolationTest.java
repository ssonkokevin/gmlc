package com.hamiltel.gmlc.consistency;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.cdr.CdrRepository;
import com.hamiltel.gmlc.config.ConfigRepository;
import com.hamiltel.gmlc.geofence.GeofenceGeometry;
import com.hamiltel.gmlc.geofence.GeofenceRepository;
import com.hamiltel.gmlc.location.LocationEstimate;
import com.hamiltel.gmlc.location.LocationRequestRepository;
import com.hamiltel.gmlc.location.LocationResultRepository;
import com.hamiltel.gmlc.location.RequestState;
import com.hamiltel.gmlc.ops.OpsAlertRepository;
import com.hamiltel.gmlc.ops.OpsAuditRepository;
import com.hamiltel.gmlc.ops.OpsMonitoringRepository;
import com.hamiltel.gmlc.ops.OpsRole;
import com.hamiltel.gmlc.ops.OpsUserRepository;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import com.hamiltel.gmlc.tracking.NotificationChannelRepository;
import com.hamiltel.gmlc.tracking.TrackingRepository;
import com.hamiltel.gmlc.tracking.TrackingScheduleRepository;
import com.hamiltel.gmlc.webhook.EventSubscriptionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every table keys on {@code tenant_id} and every repository query carries it,
 * but the read side alone does not prove isolation: a missing predicate in an
 * UPDATE or a lookup is silent until someone reads another tenant's row.
 *
 * <p>This test seeds one tenant and then uses the second tenant's identity on
 * every read and mutation. It asserts both halves — tenant B sees nothing of
 * tenant A's, and tenant A's rows are unchanged after B's attempt.
 *
 * <p>The two tenants are dedicated to this test so it can run inside the shared
 * embedded database without disturbing the suites that use tenant 1.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantIsolationTest {

    private static final long TENANT_A = 9_100_001L;
    private static final long TENANT_B = 9_100_002L;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LocationRequestRepository requests;
    @Autowired
    private LocationResultRepository results;
    @Autowired
    private CdrRepository cdrs;
    @Autowired
    private OpsMonitoringRepository monitoring;
    @Autowired
    private GeofenceRepository geofences;
    @Autowired
    private EventSubscriptionRepository subscriptions;
    @Autowired
    private TrackingRepository tracked;
    @Autowired
    private TrackingScheduleRepository schedules;
    @Autowired
    private NotificationChannelRepository channels;
    @Autowired
    private OpsAlertRepository alerts;
    @Autowired
    private OpsUserRepository users;
    @Autowired
    private OpsAuditRepository auditRows;
    @Autowired
    private AuditTrail audit;
    @Autowired
    private ConfigRepository config;

    private UUID clientA;
    private UUID clientB;
    private UUID userA;
    private UUID userB;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @BeforeAll
    void seedTenantsClientsAndUsers() {
        insertTenant(TENANT_A, "isolation-a");
        insertTenant(TENANT_B, "isolation-b");
        clientA = insertClient(TENANT_A, "isolation-client-a");
        clientB = insertClient(TENANT_B, "isolation-client-b");
        userA = UUID.randomUUID();
        userB = UUID.randomUUID();
        users.insert(userA, TENANT_A, "isolation-user-a", "Isolation A", OpsRole.VIEWER,
                "hash-a", "totp-a", true);
        users.insert(userB, TENANT_B, "isolation-user-b", "Isolation B", OpsRole.ADMIN,
                "hash-b", "totp-b", true);
    }

    @Test
    void requestsResultsCdrsAndAuditRowsAreTenantScoped() {
        Instant now = Instant.now();
        UUID requestId = UUID.randomUUID();
        String idempotencyKey = "isolation-" + requestId;

        assertThat(requests.insert(requestId, TENANT_A, clientA, idempotencyKey, "TEST",
                "256700000001", null, 300, "NORMAL", "MAP", now.plusSeconds(60))).isTrue();

        assertThat(requests.findByKey(TENANT_B, clientA, idempotencyKey)).isEmpty();
        assertThat(requests.find(TENANT_B, clientA, requestId)).isEmpty();
        assertThat(requests.find(TENANT_B, clientB, requestId)).isEmpty();
        assertThat(requests.find(TENANT_A, clientA, requestId)).isPresent();

        assertThatThrownBy(() -> requests.advance(TENANT_B, requestId, RequestState.RECEIVED,
                RequestState.AUTHORISED, "ISOLATION", "not yours"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requests.attachResult(TENANT_B, requestId, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(requests.find(TENANT_A, clientA, requestId).orElseThrow().stateValue())
                .isEqualTo(RequestState.RECEIVED);

        UUID resultId = UUID.randomUUID();
        results.insert(TENANT_A, requestId, resultId, estimate(now));
        assertThat(results.find(TENANT_B, resultId)).isEmpty();
        assertThat(results.find(TENANT_A, resultId)).isPresent();

        cdrs.writeFailure(TENANT_A, requestId, clientA, null, ref(), "TIMEOUT", 300, "MAP", 150L);
        assertThat(cdrs.recent(TENANT_A, clientA, 10)).hasSize(1);
        assertThat(cdrs.recent(TENANT_B, clientA, 10)).isEmpty();

        Instant from = now.minusSeconds(3_600);
        Instant to = now.plusSeconds(3_600);
        OpsMonitoringRepository.ExchangeFilter all =
                new OpsMonitoringRepository.ExchangeFilter(null, null, null);
        assertThat(monitoring.exchangeCount(TENANT_A, from, to, all)).isEqualTo(1);
        assertThat(monitoring.exchangeCount(TENANT_B, from, to, all)).isZero();
        assertThat(monitoring.exchanges(TENANT_B, from, to, all, 50, 0)).isEmpty();

        audit.recordOperator(TENANT_A, userA, "ISOLATION_PROBE", AuditTrail.Outcome.ALLOWED,
                Map.<String, Object>of("probe", "tenant-isolation"));
        assertThat(auditRows.list(TENANT_A, "ISOLATION_PROBE", null, null, from, to, null, 50))
                .hasSize(1);
        assertThat(auditRows.list(TENANT_B, "ISOLATION_PROBE", null, null, from, to, null, 50))
                .isEmpty();
    }

    @Test
    void geofencesArmsAndEventsAreTenantScoped() {
        UUID geofenceId = UUID.randomUUID();
        String name = "isolation-geofence-" + geofenceId.toString().substring(0, 8);
        String subjectRef = ref();

        geofences.insert(TENANT_A, geofenceId, name, "desc", "group",
                GeofenceGeometry.circle(0.3476, 32.5825, 500), false, userA);

        assertThat(geofences.find(TENANT_B, geofenceId)).isEmpty();
        assertThat(geofences.list(TENANT_B))
                .noneMatch(view -> view.geofenceId().equals(geofenceId));
        assertThat(geofences.exists(TENANT_B, name)).isFalse();
        assertThat(geofences.existsOther(TENANT_B, geofenceId, name)).isFalse();
        assertThat(geofences.arm(geofenceId, TENANT_A, subjectRef, "2567***001", userA)).isTrue();
        assertThat(geofences.arms(TENANT_B, geofenceId)).isEmpty();

        geofences.update(TENANT_B, geofenceId, "hijacked", "hijacked", "group", true, false);
        assertThat(geofences.find(TENANT_A, geofenceId).orElseThrow().name()).isEqualTo(name);

        assertThat(geofences.disarm(geofenceId, TENANT_B, subjectRef)).isFalse();
        assertThat(geofences.arms(TENANT_A, geofenceId)).hasSize(1);

        UUID resultId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Long eventId = geofences.recordEvaluation(TENANT_A, geofenceId, subjectRef, "2567***001",
                null, true, false, resultId, requestId, Instant.now(),
                0.3476, 32.5825, 100, "CELL_ID");
        assertThat(eventId).isNotNull();

        Instant from = Instant.now().minusSeconds(3_600);
        Instant to = Instant.now().plusSeconds(3_600);
        assertThat(geofences.eventCount(TENANT_A, from, to, null, geofenceId)).isEqualTo(1);
        assertThat(geofences.eventCount(TENANT_B, from, to, null, geofenceId)).isZero();
        assertThat(geofences.events(TENANT_B, from, to, null, geofenceId, 50, 0)).isEmpty();
    }

    @Test
    void subscriptionsAndDeliveriesAreTenantScoped() {
        UUID geofenceId = UUID.randomUUID();
        geofences.insert(TENANT_A, geofenceId, "isolation-hook-geofence", null, null,
                GeofenceGeometry.circle(0.3476, 32.5825, 500), false, userA);
        String subjectRef = ref();
        geofences.arm(geofenceId, TENANT_A, subjectRef, "2567***002", userA);
        Long eventId = geofences.recordEvaluation(TENANT_A, geofenceId, subjectRef, "2567***002",
                null, true, false, UUID.randomUUID(), UUID.randomUUID(), Instant.now(),
                0.3476, 32.5825, 100, "CELL_ID");
        assertThat(eventId).isNotNull();

        UUID subscriptionId = UUID.randomUUID();
        String name = "isolation-hook-" + subscriptionId.toString().substring(0, 8);
        subscriptions.insert(TENANT_A, subscriptionId, name, "http://127.0.0.1:1/hook",
                "isolation-secret", List.of("ENTER"), geofenceId, true, userA);

        assertThat(subscriptions.find(TENANT_B, subscriptionId)).isEmpty();
        assertThat(subscriptions.list(TENANT_B))
                .noneMatch(view -> view.subscriptionId().equals(subscriptionId));
        assertThat(subscriptions.existsName(TENANT_B, name, null)).isFalse();
        assertThat(subscriptions.delete(TENANT_B, subscriptionId)).isFalse();

        subscriptions.update(TENANT_B, subscriptionId, "hijacked", "http://127.0.0.1:1/evil",
                List.of("ENTER"), geofenceId, false);
        assertThat(subscriptions.find(TENANT_A, subscriptionId).orElseThrow().name())
                .isEqualTo(name);
        assertThat(subscriptions.find(TENANT_A, subscriptionId).orElseThrow().url())
                .isEqualTo("http://127.0.0.1:1/hook");

        assertThat(subscriptions.enqueue(TENANT_B, eventId)).isZero();
        assertThat(subscriptions.pendingCount(TENANT_B)).isZero();
        assertThat(subscriptions.deliveries(TENANT_B, subscriptionId, 50)).isEmpty();

        assertThat(subscriptions.enqueue(TENANT_A, eventId)).isEqualTo(1);
        assertThat(subscriptions.deliveries(TENANT_A, subscriptionId, 50)).hasSize(1);
    }

    @Test
    void trackedSubjectsSchedulesRunsAndChannelsAreTenantScoped() {
        Instant now = Instant.now();

        UUID channelId = UUID.randomUUID();
        channels.insert(channelId, TENANT_A, "isolation-channel", "WEBHOOK",
                "http://127.0.0.1:1/notify", "sealed-secret", true, userA);
        assertThat(channels.find(TENANT_B, channelId)).isEmpty();
        assertThat(channels.list(TENANT_B))
                .noneMatch(view -> view.channelId().equals(channelId));
        assertThat(channels.count(TENANT_B)).isZero();
        assertThat(channels.existsName(TENANT_B, "isolation-channel")).isFalse();
        assertThat(channels.findForDelivery(TENANT_B, channelId)).isEmpty();
        assertThat(channels.setEnabled(TENANT_B, channelId, false)).isFalse();
        assertThat(channels.delete(TENANT_B, channelId)).isFalse();
        assertThat(channels.find(TENANT_A, channelId).orElseThrow().enabled()).isTrue();

        UUID trackedId = UUID.randomUUID();
        String subjectRef = ref();
        assertThat(tracked.insert(trackedId, TENANT_A, clientA, subjectRef, "256700000003",
                null, "Isolation Subject", "SAFETY", userA)).isTrue();

        assertThat(tracked.find(TENANT_B, trackedId)).isEmpty();
        assertThat(tracked.list(TENANT_B))
                .noneMatch(view -> view.trackedId().equals(trackedId));
        assertThat(tracked.findOwner(TENANT_B, clientA, trackedId)).isEmpty();
        assertThat(tracked.findTarget(TENANT_B, trackedId)).isEmpty();
        assertThat(tracked.trail(TENANT_B, trackedId, 50)).isEmpty();
        assertThat(tracked.setEnabled(TENANT_B, trackedId, false)).isFalse();
        assertThat(tracked.delete(TENANT_B, trackedId)).isFalse();
        assertThat(tracked.find(TENANT_A, trackedId).orElseThrow().enabled()).isTrue();

        UUID scheduleId = UUID.randomUUID();
        assertThat(schedules.insert(scheduleId, TENANT_A, trackedId, 600, now, null, 300, true,
                channelId, true, now.plusSeconds(600), userA)).isTrue();

        assertThat(schedules.find(TENANT_B, scheduleId)).isEmpty();
        assertThat(schedules.list(TENANT_B))
                .noneMatch(view -> view.scheduleId().equals(scheduleId));
        assertThat(schedules.setEnabled(TENANT_B, scheduleId, false, now)).isFalse();
        assertThat(schedules.delete(TENANT_B, scheduleId)).isFalse();
        assertThat(schedules.find(TENANT_A, scheduleId).orElseThrow().enabled()).isTrue();

        assertThat(schedules.insertRun(TENANT_A, scheduleId, "MANUAL", "LOCATED", null, null,
                "NONE", null)).isPositive();
        assertThat(schedules.runs(TENANT_B, scheduleId, 50)).isEmpty();
        assertThat(schedules.runs(TENANT_A, scheduleId, 50)).hasSize(1);
    }

    @Test
    void alertsUsersAndConfigRowsAreTenantScoped() {
        String dedupeKey = "isolation-alert-" + UUID.randomUUID().toString().substring(0, 8);
        alerts.raise(TENANT_A, dedupeKey, OpsAlertRepository.Kind.PEER_DOWN,
                OpsAlertRepository.Severity.WARNING, "isolation alert",
                Map.<String, Object>of("node", "lab"));

        OpsAlertRepository.AlertRow alertA = alerts.list(TENANT_A,
                        OpsAlertRepository.State.ALL, 50).stream()
                .filter(row -> row.dedupeKey().equals(dedupeKey))
                .findFirst().orElseThrow();
        assertThat(alerts.find(TENANT_B, alertA.alertId())).isEmpty();
        assertThat(alerts.list(TENANT_B, OpsAlertRepository.State.ALL, 50))
                .noneMatch(row -> row.dedupeKey().equals(dedupeKey));
        assertThat(alerts.acknowledge(TENANT_B, alertA.alertId(), userB)).isFalse();
        assertThat(alerts.find(TENANT_A, alertA.alertId()).orElseThrow().acknowledgedAt())
                .isNull();

        assertThat(users.findByUsername(TENANT_B, "isolation-user-a")).isEmpty();
        assertThat(users.find(TENANT_B, userA)).isEmpty();
        assertThat(users.list(TENANT_B))
                .noneMatch(user -> user.userId().equals(userA));
        users.updateRole(TENANT_B, userA, OpsRole.ADMIN);
        users.setEnabled(TENANT_B, userA, false);
        users.changePassword(TENANT_B, userA, "hijacked-hash");
        assertThat(users.find(TENANT_A, userA).orElseThrow().role()).isEqualTo(OpsRole.VIEWER);
        assertThat(users.find(TENANT_A, userA).orElseThrow().enabled()).isTrue();
        assertThat(users.find(TENANT_A, userA).orElseThrow().passwordHash()).isEqualTo("hash-a");

        String configKey = "isolation." + UUID.randomUUID();
        assertThat(config.upsertItem(TENANT_A, configKey, "\"value-a\"", 0, 1, userA)).isTrue();
        assertThat(config.item(TENANT_B, configKey)).isEmpty();
        assertThat(config.version(TENANT_B, configKey)).isZero();

        UUID changeId = UUID.randomUUID();
        config.insertChange(changeId, TENANT_A, configKey, 1, "\"value-a\"", "\"value-b\"",
                "isolation test reason", true, userA, Instant.now());
        assertThat(config.change(TENANT_B, changeId)).isEmpty();
        assertThat(config.changes(TENANT_B, null, 50)).isEmpty();
        assertThat(config.decide(changeId, TENANT_B, "REJECTED", userB, "not yours", null,
                Instant.now())).isFalse();
        assertThat(config.change(TENANT_A, changeId).orElseThrow().status()).isEqualTo("PENDING");

        config.upsertItem(TENANT_B, configKey, "\"hijacked\"", 0, 1, userB);
        assertThat(config.item(TENANT_A, configKey).orElseThrow().valueJson())
                .contains("value-a");
    }

    private void insertTenant(long tenantId, String name) {
        jdbc.update("INSERT INTO tenant (tenant_id, name) VALUES (?, ?)", tenantId, name);
    }

    private UUID insertClient(long tenantId, String name) {
        UUID clientId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO api_client (client_id, tenant_id, name, key_hash, purposes,
                    max_accuracy_m, rate_per_second, rate_per_day)
                VALUES (?, ?, ?, ?, ARRAY['TEST'], 5000, 5, 1000)
                """, clientId, tenantId, name, uuidHex() + uuidHex());
        return clientId;
    }

    private static String uuidHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String ref() {
        return uuidHex() + uuidHex();
    }

    private static LocationEstimate estimate(Instant producedAt) {
        return new LocationEstimate("LTE", "CELL_ID", "CIRCLE",
                new BigDecimal("0.3476000"), new BigDecimal("32.5825000"),
                500, 70, 90, 5, true, 300, "cell-1", "CELL_DB",
                "gmlc-1", producedAt, List.of("SERVING_CELL"));
    }
}
