package com.hamiltel.gmlc.perf;

import com.hamiltel.gmlc.location.LocationEstimate;
import com.hamiltel.gmlc.location.LocationLocator;
import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures the gateway's own cost on the cell-ID request path. The name does
 * not match Surefire's default include patterns on purpose: it is a load
 * harness, not a regression test, and it only runs when asked for by name.
 *
 * <p>The positioning source is a test-only stub that answers instantly with a
 * fully qualified estimate. That is deliberate: the number this produces is
 * the cost of authentication, quota, consent, the state machine, the result,
 * the CDR and the audit row, plus Tomcat and PostgreSQL. It is not a
 * measurement of a live HSS/MME exchange, which no host here can provide.
 *
 * <p>Run:
 * {@code mvn -o -Dtest=CellIdThroughputPerf -Dperf.concurrency=32 test}
 * Properties: {@code perf.concurrency}, {@code perf.clients} (distinct API
 * clients, for the quota-contention control), {@code perf.warmupSeconds},
 * {@code perf.seconds}, {@code perf.subjects}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CellIdThroughputPerf.StubLocator.class)
class CellIdThroughputPerf {

    @TestConfiguration
    static class StubLocator {

        @Bean
        @Primary
        LocationLocator stubLocator() {
            return new LocationLocator() {
                @Override
                public Optional<LocationEstimate> locate(Query query) {
                    return Optional.of(new LocationEstimate("LTE", "CELL_ID", "CIRCLE",
                            new BigDecimal("0.347600"), new BigDecimal("32.582500"),
                            350, 70, 80, 0, true, query.accuracyMeters(),
                            "001010000000001", "CELL_DB", "MME", Instant.now(),
                            List.of("SLH_RIR", "SLG_PLA")));
                }

                @Override
                public String protocol() {
                    return "DIAMETER";
                }
            };
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private javax.sql.DataSource dataSource;

    @LocalServerPort
    private int port;

    private static int property(String name, int fallback) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    @Test
    void measure() throws Exception {
        int concurrency = property("perf.concurrency", 32);
        int warmupSeconds = property("perf.warmupSeconds", 5);
        int seconds = property("perf.seconds", 20);
        int subjects = property("perf.subjects", 64);
        int clients = property("perf.clients", 1);

        List<Tenant> tenants = new ArrayList<>(clients);
        for (int c = 0; c < clients; c++) {
            String apiKey = registerClient();
            List<String> consented = consents(apiKey, 9_000_000 + c * 1_000, subjects);
            List<String> refused = new ArrayList<>(subjects);
            for (int i = 0; i < subjects; i++) {
                refused.add("2567" + String.format("%08d", 8_000_000 + c * 1_000 + i));
            }
            tenants.add(new Tenant(apiKey, consented, refused));
        }

        System.out.println("PERF host=8-logical-cpu/16GB java="
                + System.getProperty("java.version") + " concurrency=" + concurrency
                + " clients=" + clients + " warmupS=" + warmupSeconds
                + " measuredS=" + seconds + " subjectsPerClient=" + subjects);
        for (String setting : List.of("fsync", "synchronous_commit", "shared_buffers",
                "max_connections")) {
            System.out.println("PERF pg " + setting + "="
                    + jdbc.queryForObject("show " + setting, String.class));
        }
        if (dataSource instanceof com.zaxxer.hikari.HikariDataSource hikari) {
            System.out.println("PERF hikari maxPoolSize=" + hikari.getMaximumPoolSize()
                    + " minIdle=" + hikari.getMinimumIdle());
        }

        // The first pass warms Tomcat, Hikari, the JDBC prepared statements and
        // the JIT; its numbers are discarded.
        run(tenants, concurrency, warmupSeconds, false, false);
        measure("answered", tenants, concurrency, seconds, false);
        run(tenants, concurrency, warmupSeconds, false, true);
        measure("refused", tenants, concurrency, seconds, true);
    }

    private Scenario measure(String name, List<Tenant> tenants, int concurrency,
                             int seconds, boolean expectRefusal) throws Exception {
        long commitsBefore = commits();
        Scenario scenario = run(tenants, concurrency, seconds, true, expectRefusal);
        long commitsAfter = commits();
        System.out.println("PERF " + name + " " + scenario);
        System.out.printf("PERF %s sql-statements-per-request=%.2f%n", name,
                scenario.requests() == 0 ? 0.0
                        : (commitsAfter - commitsBefore) / (double) scenario.requests());
        if (scenario.failures() != 0) {
            throw new AssertionError("the " + name + " harness saw " + scenario.failures()
                    + " failed requests; the throughput figure is not trustworthy");
        }
        return scenario;
    }

    private String registerClient() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "perf-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        body.put("purposes", List.of("TEST"));
        body.put("maxAccuracyM", 5_000);
        body.put("ratePerSecond", 1_000);
        body.put("ratePerDay", 1_000_000);
        ResponseEntity<com.fasterxml.jackson.databind.JsonNode> response = rest.exchange(
                "/v1/admin/clients", HttpMethod.POST,
                new HttpEntity<>(body, EmbeddedPostgresSupport.adminHeaders()),
                com.fasterxml.jackson.databind.JsonNode.class);
        if (response.getStatusCode() != HttpStatus.CREATED || response.getBody() == null) {
            throw new IllegalStateException("client registration failed: " + response.getStatusCode());
        }
        return response.getBody().path("apiKey").asText();
    }

    private List<String> consents(String apiKey, int first, int count) {
        List<String> msisdns = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String msisdn = "2567" + String.format("%08d", first + i);
            HttpHeaders headers = EmbeddedPostgresSupport.bearer(apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> response = rest.exchange("/v1/consents", HttpMethod.POST,
                    new HttpEntity<>(Map.of("msisdn", msisdn, "purpose", "TEST", "channel", "WEB"),
                            headers), String.class);
            if (response.getStatusCode() != HttpStatus.CREATED) {
                throw new IllegalStateException("consent failed for subject " + i
                        + ": " + response.getStatusCode());
            }
            msisdns.add(msisdn);
        }
        return msisdns;
    }

    private Scenario run(List<Tenant> tenants, int concurrency, int seconds,
                         boolean collectLatencies, boolean expectRefusal) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        AtomicLong requests = new AtomicLong();
        AtomicInteger failures = new AtomicInteger();
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        long endAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        int workerCount = concurrency;

        for (int i = 0; i < workerCount; i++) {
            final int worker = i;
            pool.execute(() -> {
                Tenant tenant = tenants.get(worker % tenants.size());
                List<String> msisdns = expectRefusal ? tenant.refused() : tenant.consented();
                String apiKey = tenant.apiKey();
                int subjectCount = msisdns.size();
                int subject = worker % subjectCount;
                ready.countDown();
                try {
                    start.await();
                    while (System.nanoTime() < endAt) {
                        String msisdn = msisdns.get(subject);
                        HttpRequest request = HttpRequest.newBuilder(
                                        URI.create("http://localhost:" + port + "/v1/locations"))
                                .timeout(Duration.ofSeconds(10))
                                .header("Authorization", "Bearer " + apiKey)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"msisdn\":\"" + msisdn + "\",\"purpose\":\"TEST\"}"))
                                .build();
                        long began = System.nanoTime();
                        try {
                            HttpResponse<String> response =
                                    client.send(request, HttpResponse.BodyHandlers.ofString());
                            long elapsed = System.nanoTime() - began;
                            boolean accepted = expectRefusal
                                    ? response.statusCode() == 403
                                            && response.body().contains("PRIVACY_NOT_SATISFIED")
                                    : response.statusCode() == 200
                                            && response.body().contains("\"state\":\"DELIVERED\"");
                            if (accepted) {
                                requests.incrementAndGet();
                                if (collectLatencies) {
                                    latencies.add(elapsed / 1_000_000L);
                                }
                            } else {
                                failures.incrementAndGet();
                            }
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                        subject = (subject + 1) % subjectCount;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        long began = System.nanoTime();
        start.countDown();
        done.await();
        long elapsed = System.nanoTime() - began;
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        double rps = requests.get() / (elapsed / 1_000_000_000d);
        return new Scenario(concurrency, seconds, requests.get(), failures.get(), rps,
                percentile(sorted, 0.50), percentile(sorted, 0.95),
                percentile(sorted, 0.99), sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1));
    }

    private static long percentile(List<Long> sorted, double fraction) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(fraction * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private long commits() {
        Long committed = jdbc.queryForObject(
                "select xact_commit from pg_stat_database where datname = current_database()",
                Long.class);
        return committed == null ? 0 : committed;
    }

    record Tenant(String apiKey, List<String> consented, List<String> refused) {
    }

    record Scenario(int concurrency, int seconds, long requests, int failures, double rps,
                    long p50ms, long p95ms, long p99ms, long maxMs) {

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "concurrency=%d seconds=%d requests=%d rps=%.1f p50ms=%d p95ms=%d"
                            + " p99ms=%d maxMs=%d failures=%d",
                    concurrency, seconds, requests, rps, p50ms, p95ms, p99ms, maxMs, failures);
        }
    }
}
