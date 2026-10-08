package lab;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import static lab.Model.*;

@Component
public class BenchmarkService {
    public static final List<String> SCENARIOS = List.of("insert", "update", "mixed", "unchanged");
    private final DataSource ds;
    private final JdbcTemplate db;
    private final Transformer transformer;
    private final Writers writers;
    private final int userBatch;

    public BenchmarkService(DataSource ds, Transformer transformer, Writers writers,
                            @Value("${USER_BATCH_SIZE:500}") int userBatch) {
        if (userBatch < 1) throw new IllegalArgumentException("USER_BATCH_SIZE must be positive");
        this.ds = ds;
        db = new JdbcTemplate(ds);
        this.transformer = transformer;
        this.writers = writers;
        this.userBatch = userBatch;
    }

    public void assertLabDatabase() {
        String name = db.queryForObject("SELECT current_database()", String.class);
        if (!"jsonb_migration_lab".equals(name))
            throw new IllegalStateException("This program may ONLY reset database jsonb_migration_lab");
    }

    public void setup(int maxUsers) throws Exception {
        assertLabDatabase();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        // Reset only the four LAB tables. Source generation is outside migration timing.
        db.execute("TRUNCATE lab_java.user_reward_group,lab_java.user_coupon,lab_java.user_benefit,lab_java.m_user");
        String sql;
        try (var in = new ClassPathResource("seed.sql").getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        db.update(sql, maxUsers);
        db.execute("ANALYZE lab_java.m_user");
    }

    public void resetTargets() {
        assertLabDatabase();
        db.execute("TRUNCATE lab_java.user_reward_group,lab_java.user_coupon,lab_java.user_benefit");
    }

    /**
     * Every trial gets a method-independent baseline, outside the migration timer.
     */
    public void prepareTrial(String scenario, int users) {
        if (!SCENARIOS.contains(scenario)) throw new IllegalArgumentException("Unknown scenario: " + scenario);
        if (users < 1 || users > 100_000) throw new IllegalArgumentException("Invalid user count");
        resetTargets();
        if (scenario.equals("insert")) return;
        int existing = scenario.equals("mixed") ? users / 2 : users;
        db.update("INSERT INTO lab_java.user_reward_group (user_id,reward_group_id,file_name,period_start,period_end) " + expectedGroups(), userId(existing));
        db.update("INSERT INTO lab_java.user_coupon (user_id,coupon_id,reward_group_id,coupon_code,quota_used,usage_daily,usage_weekly,usage_monthly,update_date) " + expectedCoupons(), userId(existing));
        db.update("INSERT INTO lab_java.user_benefit (user_id,period_type,amount,end_date,last_update) " + expectedBenefits(), userId(existing));
        if (!scenario.equals("unchanged")) {
            // Deterministically stale values: every existing row needs an update.
            db.update("UPDATE lab_java.user_reward_group SET file_name='OLD:'||coalesce(file_name,'NULL')");
            db.update("UPDATE lab_java.user_coupon SET quota_used=quota_used+1, update_date=CASE WHEN update_date IS NULL THEN timestamptz '2000-01-01 00:00:00+00' ELSE NULL END");
            db.update("UPDATE lab_java.user_benefit SET amount=amount+1");
        }
        db.execute("ANALYZE lab_java.user_reward_group");
        db.execute("ANALYZE lab_java.user_coupon");
        db.execute("ANALYZE lab_java.user_benefit");
    }

    /**
     * Used outside the timer to prove an unchanged trial did not issue physical updates.
     */
    public String targetVersions() {
        return db.queryForObject("SELECT md5(coalesce(string_agg(v,',' ORDER BY v),'')) FROM ("
                + "SELECT 'g:'||user_id||':'||reward_group_id||':'||xmin::text AS v FROM lab_java.user_reward_group UNION ALL "
                + "SELECT 'c:'||user_id||':'||coupon_id||':'||xmin::text FROM lab_java.user_coupon UNION ALL "
                + "SELECT 'b:'||user_id||':'||period_type||':'||xmin::text FROM lab_java.user_benefit) versions", String.class);
    }

    public List<Source> read(String after, int users) {
        // IDs are padded, so string keyset order agrees with the dummy user number.
        return db.query("SELECT id,\"group\"::text,coupons::text,benefit::text FROM lab_java.m_user WHERE id>? AND id<=? ORDER BY id LIMIT ?",
                (r, i) -> new Source(r.getString(1), r.getString(2), r.getString(3), r.getString(4)),
                after, userId(users), userBatch);
    }

    public static String userId(int n) {
        return "U" + String.format(java.util.Locale.ROOT, "%08d", n);
    }

    public record Batch(int number, String fromId, String toId, int users, int groups, int coupons, int benefits,
                        double totalMs, double readMs, double transformMs, double writeCommitMs) {
    }

    public record Migration(String method, int users, int batchSize, List<Batch> batches,
                            long groups, long coupons, long benefits, double totalMs,
                            double readMs, double transformMs, double writeCommitMs) {
    }

    public Migration migrate(String method, int users) throws Exception {
        String after = "";
        long groups = 0, coupons = 0, benefits = 0;
        int processed = 0;
        long readNanos = 0, transformNanos = 0, writeNanos = 0;
        var batches = new ArrayList<Batch>();
        long started = System.nanoTime();
        while (true) {
            long batchStart = System.nanoTime();
            var source = read(after, users);
            long readEnd = System.nanoTime();
            readNanos += readEnd - batchStart;
            if (source.isEmpty()) break;
            Rows rows = transformer.transform(source);
            long transformEnd = System.nanoTime();
            writers.write(method, rows); // Includes flush/executeBatch and COMMIT.
            long writeEnd = System.nanoTime();
            transformNanos += transformEnd - readEnd;
            writeNanos += writeEnd - transformEnd;
            processed += source.size();
            groups += rows.groups().size();
            coupons += rows.coupons().size();
            benefits += rows.benefits().size();
            after = source.get(source.size() - 1).id();
            batches.add(new Batch(batches.size() + 1, source.get(0).id(), after, source.size(),
                    rows.groups().size(), rows.coupons().size(), rows.benefits().size(),
                    ms(writeEnd - batchStart), ms(readEnd - batchStart), ms(transformEnd - readEnd), ms(writeEnd - transformEnd)));
        }
        long ended = System.nanoTime();
        if (processed != users) throw new IllegalStateException("Expected " + users + " users, read " + processed);
        return new Migration(method, users, userBatch, batches, groups, coupons, benefits,
                ms(ended - started), ms(readNanos), ms(transformNanos), ms(writeNanos));
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }

    private String source(String column) {
        return " FROM lab_java.m_user u CROSS JOIN LATERAL jsonb_each(u." + column + ") e WHERE u.id<=?";
    }

    private String expectedGroups() {
        return "SELECT u.id AS user_id,e.key AS item_id,e.value->>'fileName',(e.value->>'periodStart')::timestamptz,(e.value->>'periodEnd')::timestamptz"
                + source("\"group\"");
    }

    private String expectedCoupons() {
        return "SELECT u.id AS user_id,e.key AS item_id,e.value->>'rewardGroupId',e.value->>'couponCode',(e.value->>'quotaUsed')::integer,(e.value->>'usageDaily')::integer,(e.value->>'usageWeekly')::integer,(e.value->>'usageMonthly')::integer,(e.value->>'updateDate')::timestamptz"
                + source("coupons");
    }

    private String expectedBenefits() {
        return "SELECT u.id AS user_id,e.key AS item_id,(e.value->>'amount')::numeric,(e.value->>'endDate')::timestamptz,(e.value->>'lastUpdate')::timestamptz"
                + source("benefit");
    }

    /**
     * Independent SQL comparison of supplied keys. Absent source items may remain in target.
     */
    public void validate(int users) {
        equivalent("groups", expectedGroups(), "SELECT user_id,reward_group_id AS item_id,file_name,period_start,period_end FROM lab_java.user_reward_group", users);
        equivalent("coupons", expectedCoupons(), "SELECT user_id,coupon_id AS item_id,reward_group_id,coupon_code,quota_used,usage_daily,usage_weekly,usage_monthly,update_date FROM lab_java.user_coupon", users);
        equivalent("benefits", expectedBenefits(), "SELECT user_id,period_type AS item_id,amount,end_date,last_update FROM lab_java.user_benefit", users);
    }

    /**
     * Benchmark fixtures contain no retained keys, so require exact target counts too.
     */
    public void validateTargetCounts(Migration migration) {
        requireCount("lab_java.user_reward_group", migration.groups());
        requireCount("lab_java.user_coupon", migration.coupons());
        requireCount("lab_java.user_benefit", migration.benefits());
    }

    private void requireCount(String table, long expected) {
        Long actual = db.queryForObject("SELECT count(*) FROM " + table, Long.class);
        if (actual == null || actual != expected)
            throw new IllegalStateException("Unexpected target count in " + table + ": " + actual + ", expected " + expected);
    }

    private void equivalent(String name, String expected, String actual, int users) {
        String sql = "WITH expected AS (" + expected + "), actual_all AS (" + actual + ")"
                + ", actual AS (SELECT a.* FROM actual_all a JOIN expected e ON a.user_id=e.user_id AND a.item_id=e.item_id)"
                + " SELECT count(*) FROM ((SELECT * FROM expected EXCEPT ALL SELECT * FROM actual) UNION ALL (SELECT * FROM actual EXCEPT ALL SELECT * FROM expected)) differences";
        Long mismatches = db.queryForObject(sql, Long.class, userId(users));
        if (mismatches == null || mismatches != 0)
            throw new IllegalStateException("Validation FAILED for " + name + ": " + mismatches + " different rows");
    }

    public long jsonBytes(int users) {
        return db.queryForObject("SELECT coalesce(sum(octet_length(\"group\"::text)+octet_length(coupons::text)+octet_length(benefit::text)),0) FROM lab_java.m_user WHERE id<=?", Long.class, userId(users));
    }

    public String environment() {
        return "PostgreSQL=" + db.queryForObject("SELECT version()", String.class) + "\n"
                + "Java=" + System.getProperty("java.version") + "\n"
                + "OS=" + System.getProperty("os.name") + " " + System.getProperty("os.arch") + "\n"
                + "availableProcessors=" + Runtime.getRuntime().availableProcessors() + "\n"
                + "JVM max heap bytes=" + Runtime.getRuntime().maxMemory() + "\n"
                + "shared_buffers=" + db.queryForObject("SHOW shared_buffers", String.class) + "\n"
                + "synchronous_commit=" + db.queryForObject("SHOW synchronous_commit", String.class) + "\n";
    }

    public Connection acquireLabLock() throws Exception {
        assertLabDatabase();
        Connection c = ds.getConnection();
        try (var statement = c.createStatement(); var rs = statement.executeQuery("SELECT pg_try_advisory_lock(69106910)")) {
            rs.next();
            if (!rs.getBoolean(1)) {
                c.close();
                throw new IllegalStateException("Another benchmark is running");
            }
            return c;
        } catch (Exception ex) {
            if (!c.isClosed()) c.close();
            throw ex;
        }
    }

    public void releaseLabLock(Connection c) throws Exception {
        try (var s = c.createStatement()) {
            s.execute("SELECT pg_advisory_unlock(69106910)");
        }
    }
}
