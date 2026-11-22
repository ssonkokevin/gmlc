package com.hamiltel.gmlc.consistency;

import com.hamiltel.gmlc.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Flyway migrations are the only source of the database schema. This test
 * fingerprints the database that Flyway built and compares it with the checked-in
 * snapshot, so a migration edit without a regenerated snapshot fails.
 *
 * <p>Regenerate the snapshot after an intentional schema change:
 * <pre>
 * mvn -o -pl gmlc-app test -Dtest=SchemaSnapshotTest -Dgmlc.schema.snapshot=write
 * cp gmlc-app/target/schema.snapshot.sql gmlc-app/src/test/resources/schema.snapshot.sql
 * </pre>
 * The generated file lands in {@code target/} so a test never edits the source
 * tree. {@code flyway_schema_history} is Flyway's own bookkeeping and is
 * excluded.
 */
@SpringBootTest
class SchemaSnapshotTest {

    private static final String EXCLUDED = "flyway_schema_history";

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry);
    }

    @Test
    void databaseSchemaMatchesTheCommittedSnapshot() throws Exception {
        String actual = snapshot();
        if ("write".equals(System.getProperty("gmlc.schema.snapshot"))
                || "write".equals(System.getenv("GMLC_SCHEMA_SNAPSHOT"))) {
            Path generated = Paths.get("target", "schema.snapshot.sql");
            Files.createDirectories(generated.getParent());
            Files.writeString(generated, actual, StandardCharsets.UTF_8);
            System.out.println("schema snapshot written to " + generated.toAbsolutePath());
            return;
        }

        String expected;
        try (InputStream in = getClass().getResourceAsStream("/schema.snapshot.sql")) {
            assertThat(in)
                    .as("gmlc-app/src/test/resources/schema.snapshot.sql is missing; "
                            + "regenerate it with -Dgmlc.schema.snapshot=write")
                    .isNotNull();
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(normalise(actual))
                .as("the database built from Flyway migrations differs from the snapshot; "
                        + "if the change is intentional, regenerate the snapshot")
                .isEqualTo(normalise(expected));
    }

    private String snapshot() {
        List<String> lines = new ArrayList<>();
        lines.add("# generated from Flyway migrations; do not edit by hand");
        lines.add("# regenerate with -Dgmlc.schema.snapshot=write");

        jdbc.queryForList("""
                select c.relname as table_name
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relkind = 'r' and c.relname <> ?
                order by c.relname
                """, String.class, EXCLUDED)
                .forEach(table -> lines.add("table " + table));

        jdbc.query("""
                select c.relname as table_name, a.attname as column_name,
                       format_type(a.atttypid, a.atttypmod) as column_type,
                       case when a.attnotnull then 'NO' else 'YES' end as nullable,
                       coalesce(pg_get_expr(d.adbin, d.adrelid), '') as column_default,
                       case a.attidentity when '' then 'NO' else 'YES' end as is_identity,
                       case a.attgenerated when '' then 'NO' else 'YES' end as is_generated
                from pg_attribute a
                join pg_class c on c.oid = a.attrelid
                join pg_namespace n on n.oid = c.relnamespace
                left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                where n.nspname = 'public' and c.relkind = 'r'
                  and a.attnum > 0 and not a.attisdropped and c.relname <> ?
                order by c.relname, a.attnum
                """, rs -> {
            lines.add("column " + rs.getString("table_name") + "." + rs.getString("column_name")
                    + " " + rs.getString("column_type")
                    + " nullable=" + rs.getString("nullable")
                    + " default=" + normaliseDefault(rs.getString("column_default"))
                    + " identity=" + rs.getString("is_identity")
                    + " generated=" + rs.getString("is_generated"));
        }, EXCLUDED);

        jdbc.query("""
                select c.relname as table_name, con.conname as name,
                       pg_get_constraintdef(con.oid, false) as definition
                from pg_constraint con
                join pg_class c on c.oid = con.conrelid
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relname <> ?
                order by c.relname, con.conname
                """, rs -> {
            lines.add("constraint " + rs.getString("table_name") + " " + rs.getString("name")
                    + " " + oneLine(rs.getString("definition")));
        }, EXCLUDED);

        jdbc.query("""
                select tablename, indexname, indexdef
                from pg_indexes
                where schemaname = 'public' and tablename <> ?
                order by tablename, indexname
                """, rs -> {
            lines.add("index " + rs.getString("tablename") + " " + rs.getString("indexname")
                    + " " + oneLine(rs.getString("indexdef")).replace("public.", ""));
        }, EXCLUDED);

        jdbc.query("""
                select sequence_name, data_type, start_value, minimum_value,
                       maximum_value, increment
                from information_schema.sequences
                where sequence_schema = 'public'
                order by sequence_name
                """, rs -> {
            lines.add("sequence " + rs.getString("sequence_name")
                    + " " + rs.getString("data_type")
                    + " start=" + rs.getString("start_value")
                    + " min=" + rs.getString("minimum_value")
                    + " max=" + rs.getString("maximum_value")
                    + " increment=" + rs.getString("increment"));
        });

        lines.sort(String::compareTo);
        return String.join("\n", lines) + "\n";
    }

    private static String normaliseDefault(String value) {
        String normalised = oneLine(value).replace("public.", "");
        normalised = normalised.replaceAll("nextval\\('([^']+)'::regclass\\)", "nextval($1)");
        return normalised;
    }

    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String normalise(String value) {
        return value.replace("\r\n", "\n").replace("\r", "\n").trim();
    }
}
