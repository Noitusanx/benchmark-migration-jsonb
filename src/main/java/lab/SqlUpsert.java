package lab;

import java.math.BigDecimal;
import java.sql.JDBCType;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static lab.Model.*;

/**
 * Shared SQL shape/diff rules. JDBC and MyBatis execute through their own APIs.
 * Read-before-write is intended for this single-writer lab, not concurrent production upsert.
 */
public final class SqlUpsert {
    private SqlUpsert() {
    }

    public record Column(String name, JDBCType type) {
    }

    public record Table(String name, String itemKey, List<Column> columns) {
    }

    public record Target(RowId id, List<Object> values) {
    }

    public record Selection(Table table, List<Target> rows) {
    }

    public record Command(String jdbcSql, String mybatisSql, List<Object> values, List<JDBCType> types) {
    }

    private static Column text(String name) {
        return new Column(name, JDBCType.VARCHAR);
    }

    private static Column time(String name) {
        return new Column(name, JDBCType.TIMESTAMP_WITH_TIMEZONE);
    }

    private static Column integer(String name) {
        return new Column(name, JDBCType.INTEGER);
    }

    public static final Table GROUP = new Table("lab_java.user_reward_group", "reward_group_id",
            List.of(text("file_name"), time("period_start"), time("period_end")));
    public static final Table COUPON = new Table("lab_java.user_coupon", "coupon_id",
            List.of(text("reward_group_id"), text("coupon_code"), integer("quota_used"), integer("usage_daily"),
                    integer("usage_weekly"), integer("usage_monthly"), time("update_date")));
    public static final Table BENEFIT = new Table("lab_java.user_benefit", "period_type",
            List.of(new Column("amount", JDBCType.NUMERIC), time("end_date"), time("last_update")));

    public static Target target(GroupRow r) {
        return new Target(new RowId(r.userId(), r.groupId()), Arrays.asList(r.fileName(), r.periodStart(), r.periodEnd()));
    }

    public static Target target(CouponRow r) {
        return new Target(new RowId(r.userId(), r.couponId()), Arrays.asList(r.rewardGroupId(), r.couponCode(),
                r.quotaUsed(), r.usageDaily(), r.usageWeekly(), r.usageMonthly(), r.updateDate()));
    }

    public static Target target(BenefitRow r) {
        return new Target(new RowId(r.userId(), r.periodType()), Arrays.asList(r.amount(), r.endDate(), r.lastUpdate()));
    }

    public static RowId key(Table table, Map<String, Object> row) {
        return new RowId((String) row.get("user_id"), (String) row.get(table.itemKey()));
    }

    public static String jdbcSelect(Selection selection) {
        return selectSql(selection, false);
    }

    public static String select(Selection selection) {
        return selectSql(selection, true);
    }

    private static String selectSql(Selection selection, boolean mapped) {
        Table table = selection.table();
        String columns = table.columns().stream().map(Column::name).collect(Collectors.joining(","));
        var keys = new ArrayList<String>();
        for (int i = 0; i < selection.rows().size(); i++) {
            keys.add(mapped ? "(#{rows[" + i + "].id.userId},#{rows[" + i + "].id.itemId})" : "(?,?)");
        }
        return "SELECT user_id," + table.itemKey() + "," + columns + " FROM " + table.name()
                + " WHERE (user_id," + table.itemKey() + ") IN (" + String.join(",", keys) + ")";
    }

    /**
     * null means no SQL is needed: all non-key values are already equal.
     */
    public static Command plan(Table table, Target target, Map<String, Object> existing) {
        var values = new ArrayList<Object>();
        var types = new ArrayList<JDBCType>();
        var columns = new ArrayList<String>();
        boolean insert = existing == null;
        if (insert) {
            columns.add("user_id");
            columns.add(table.itemKey());
            values.add(target.id().userId);
            values.add(target.id().itemId);
            types.add(JDBCType.VARCHAR);
            types.add(JDBCType.VARCHAR);
        }
        for (int i = 0; i < table.columns().size(); i++) {
            Column column = table.columns().get(i);
            Object value = target.values().get(i);
            if (insert || !same(value, existing.get(column.name()))) {
                columns.add(column.name());
                values.add(value);
                types.add(column.type());
            }
        }
        if (columns.isEmpty()) return null;
        int changed = columns.size();
        if (!insert) {
            values.add(target.id().userId);
            values.add(target.id().itemId);
            types.add(JDBCType.VARCHAR);
            types.add(JDBCType.VARCHAR);
        }
        return new Command(writeSql(table, columns, types, changed, insert, false),
                writeSql(table, columns, types, changed, insert, true), values, types);
    }

    public static String command(Command command) {
        return command.mybatisSql();
    }

    private static String parameter(int index, List<JDBCType> types, boolean mapped) {
        return mapped ? "#{values[" + index + "],jdbcType=" + types.get(index).name() + "}" : "?";
    }

    private static String writeSql(Table table, List<String> columns, List<JDBCType> types,
                                   int changed, boolean insert, boolean mapped) {
        var parameters = new ArrayList<String>();
        for (int i = 0; i < changed; i++) parameters.add(parameter(i, types, mapped));
        if (insert) return "INSERT INTO " + table.name() + " (" + String.join(",", columns)
                + ") VALUES (" + String.join(",", parameters) + ")";
        var assignments = new ArrayList<String>();
        for (int i = 0; i < changed; i++) assignments.add(columns.get(i) + "=" + parameters.get(i));
        return "UPDATE " + table.name() + " SET " + String.join(",", assignments)
                + " WHERE user_id=" + parameter(changed, types, mapped)
                + " AND " + table.itemKey() + "=" + parameter(changed + 1, types, mapped);
    }

    public static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        return Objects.equals(normalizeTime(a), normalizeTime(b));
    }

    private static Object normalizeTime(Object value) {
        if (value instanceof OffsetDateTime date) return date.toInstant();
        if (value instanceof Timestamp date) return date.toInstant();
        return value;
    }
}
