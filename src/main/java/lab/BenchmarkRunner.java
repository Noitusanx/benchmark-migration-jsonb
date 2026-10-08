package lab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static lab.BenchmarkService.*;

@Component
@ConditionalOnProperty(name = "bench.enabled", havingValue = "true", matchIfMissing = true)
public class BenchmarkRunner implements CommandLineRunner {
    private final BenchmarkService service;

    public BenchmarkRunner(BenchmarkService service) {
        this.service = service;
    }

    private record Trial(String scenario, Migration result, boolean warmup) {
    }

    private record SummaryKey(int users, String scenario, String method) {
    }

    private static final Locale ROOT = Locale.ROOT;

    @Override
    public void run(String... args) throws Exception {
        var users = Arrays.stream(env("BENCH_USERS", "1000,5000,10000").split(","))
                .map(String::trim).mapToInt(Integer::parseInt).distinct().sorted().toArray();
        if (users.length == 0 || users[0] < 1 || users[users.length - 1] > 100_000)
            throw new IllegalArgumentException("BENCH_USERS must be between 1 and 100000");
        var methods = Arrays.stream(env("METHODS", String.join(",", Writers.METHODS)).split(","))
                .map(String::trim).distinct().toList();
        if (methods.isEmpty() || !Writers.METHODS.containsAll(methods))
            throw new IllegalArgumentException("METHODS must use " + Writers.METHODS);
        var scenarios = Arrays.stream(env("SCENARIOS", String.join(",", SCENARIOS)).split(","))
                .map(String::trim).distinct().toList();
        if (scenarios.isEmpty() || !SCENARIOS.containsAll(scenarios))
            throw new IllegalArgumentException("SCENARIOS must use " + SCENARIOS);
        int repetitions = Integer.parseInt(env("REPETITIONS", "5"));
        int warmups = Integer.parseInt(env("WARMUPS", "1"));
        if (repetitions < 1 || warmups < 0) throw new IllegalArgumentException("Invalid repetition/warmup count");
        Path out = Path.of(env("OUTPUT_DIR", "results"));
        Files.createDirectories(out);
        try (var lock = service.acquireLabLock()) {
            try {
                Files.deleteIfExists(out.resolve("summary.csv"));
                long seedStart = System.nanoTime();
                service.setup(users[users.length - 1]);
                double seedMs = (System.nanoTime() - seedStart) / 1_000_000.0;
                Files.writeString(out.resolve("environment.txt"), "Started UTC=" + Instant.now() + "\n"
                        + service.environment() + "seed/setup ms (excluded)=" + seedMs + "\n"
                        + "users=" + Arrays.toString(users) + "\nmethods=" + methods + "\nscenarios=" + scenarios + "\n"
                        + "write_strategy=selective_upsert (single writer; read-before-write)\n"
                        + "repetitions=" + repetitions + "\nwarmups=" + warmups + "\n"
                        + "WRITE_BATCH_SIZE=" + env("WRITE_BATCH_SIZE", "100") + "\n"
                        + "USER_BATCH_SIZE=" + env("USER_BATCH_SIZE", "500") + "\n"
                        + "reWriteBatchedInserts=" + jdbcRewrite() + "\n"
                        + "Source: 6 groups + 10 coupons + 3 benefits per user; read-only during each run.\n"
                        + "Common source reader and JSON transformer; four different writing strategies.\n"
                        + "Each trial has independent SQL-prepared targets, excluded from timing. Target lookups ARE timed inside write_commit_ms.\n"
                        + "insert: empty; update: all stale; mixed: first floor(users/2) stale, rest absent; unchanged: all identical.\n"
                        + "Nullable values follow source; absent source items are not deleted.\n");
                Files.writeString(out.resolve("runs.csv"), "method,scenario,source_users,repetition,warmup,user_batch_size,committed_batches,group_rows,coupon_rows,benefit_rows,total_ms,read_ms,transform_ms,write_commit_ms,users_per_second,json_text_bytes,validation\n");
                Files.writeString(out.resolve("batches.csv"), "method,scenario,source_users,repetition,warmup,batch_number,from_user,to_user,users,group_rows,coupon_rows,benefit_rows,total_ms,read_ms,transform_ms,write_commit_ms\n");
                var trials = new ArrayList<Trial>();
                System.out.printf(ROOT, "Dummy ready: %,d users. Setup %.3f ms (excluded).%n", users[users.length - 1], seedMs);
                for (int count : users) {
                    long bytes = service.jsonBytes(count);
                    for (String scenario : scenarios) {
                        for (int round = 0; round < warmups; round++)
                            for (String method : order(methods, count + round + scenario.hashCode()))
                                trial(method, scenario, count, round + 1, true, bytes, out, trials);
                        for (int round = 0; round < repetitions; round++)
                            for (String method : order(methods, 42L + count + round + scenario.hashCode()))
                                trial(method, scenario, count, round + 1, false, bytes, out, trials);
                    }
                }
                summary(out, trials);
                System.out.println("Finished. Files: runs.csv, summary.csv, batches.csv, environment.txt in " + out);
                System.out.println("Tables retain the result of the LAST trial. Every trial was validated independently.");
            } finally {
                service.releaseLabLock(lock);
            }
        }
    }

    private void trial(String method, String scenario, int users, int repetition, boolean warmup, long bytes, Path out, List<Trial> trials) throws Exception {
        service.prepareTrial(scenario, users); // Preparation and validation are excluded from total_ms.
        String before = scenario.equals("unchanged") ? service.targetVersions() : null;
        Migration result = service.migrate(method, users);
        service.validate(users);
        service.validateTargetCounts(result);
        if (before != null && !before.equals(service.targetVersions()))
            throw new IllegalStateException("Unchanged scenario modified target row versions");
        trials.add(new Trial(scenario, result, warmup));
        append(out, "runs.csv", String.format(ROOT, "%s,%s,%d,%d,%b,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%d,PASS%n",
                method, scenario, users, repetition, warmup, result.batchSize(), result.batches().size(),
                result.groups(), result.coupons(), result.benefits(), result.totalMs(), result.readMs(),
                result.transformMs(), result.writeCommitMs(), users * 1000.0 / result.totalMs(), bytes));
        var lines = new StringBuilder();
        for (Batch b : result.batches())
            lines.append(String.format(ROOT, "%s,%s,%d,%d,%b,%d,%s,%s,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f%n",
                    method, scenario, users, repetition, warmup, b.number(), b.fromId(), b.toId(), b.users(), b.groups(), b.coupons(), b.benefits(),
                    b.totalMs(), b.readMs(), b.transformMs(), b.writeCommitMs()));
        append(out, "batches.csv", lines.toString());
        System.out.printf(ROOT, "%-15s %-9s users=%5d trial=%d %-8s total=%9.3f ms  write+commit=%9.3f ms  PASS%n",
                method, scenario, users, repetition, warmup ? "WARMUP" : "MEASURED", result.totalMs(), result.writeCommitMs());
    }

    private static void summary(Path out, List<Trial> trials) throws Exception {
        StringBuilder csv = new StringBuilder("method,scenario,source_users,measured_runs,median_total_ms,min_total_ms,max_total_ms,median_write_commit_ms,median_users_per_second,validation\n");
        var keys = new TreeSet<SummaryKey>(Comparator.comparingInt(SummaryKey::users)
                .thenComparing(SummaryKey::scenario).thenComparing(SummaryKey::method));
        for (Trial t : trials)
            if (!t.warmup()) keys.add(new SummaryKey(t.result().users(), t.scenario(), t.result().method()));
        System.out.println("\nSUMMARY (warmups excluded; median per scenario/method/user count)");
        for (SummaryKey key : keys) {
            int users = key.users();
            String method = key.method();
            String scenario = key.scenario();
            var results = trials.stream().filter(t -> !t.warmup() && t.result().users() == users
                    && t.scenario().equals(scenario) && t.result().method().equals(method)).map(Trial::result).toList();
            double[] totals = results.stream().mapToDouble(Migration::totalMs).sorted().toArray();
            double[] writes = results.stream().mapToDouble(Migration::writeCommitMs).sorted().toArray();
            double median = median(totals);
            csv.append(String.format(ROOT, "%s,%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,PASS%n",
                    method, scenario, users, totals.length, median, totals[0], totals[totals.length - 1], median(writes), users * 1000.0 / median));
            System.out.printf(ROOT, "%-15s %-9s users=%5d median=%9.3f ms (%d runs) PASS%n", method, scenario, users, median, totals.length);
        }
        Files.writeString(out.resolve("summary.csv"), csv.toString());
    }

    static double median(double[] sorted) {
        int n = sorted.length;
        if (n == 0) throw new IllegalArgumentException("No measured runs");
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    private static List<String> order(List<String> methods, long seed) {
        var copy = new ArrayList<>(methods);
        Collections.shuffle(copy, new Random(seed));
        return copy;
    }

    private static void append(Path dir, String file, String text) throws Exception {
        Files.writeString(dir.resolve(file), text, StandardOpenOption.APPEND);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String jdbcRewrite() {
        return env("DB_URL", "jdbc:postgresql://localhost:55434/jsonb_migration_lab?reWriteBatchedInserts=true").contains("reWriteBatchedInserts=true") ? "true" : "false";
    }
}
