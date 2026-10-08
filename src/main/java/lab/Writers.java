package lab;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.sql.*;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import javax.sql.DataSource;

import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.UpdateProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Persistable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static lab.Model.*;
import static lab.SqlUpsert.*;

/**
 * Four read-before-write strategies for a single-writer lab. No deletion of absent items.
 */
@Component
public class Writers {
    public static final List<String> METHODS = List.of("jdbc", "mybatis", "jpa_repository", "entity_manager");
    private final DataSource ds;
    private final int writeBatch;
    private final SqlSessionFactory mybatis;
    private final TransactionTemplate transactions;
    private final GroupRepository groupRepo;
    private final CouponRepository couponRepo;
    private final BenefitRepository benefitRepo;
    @PersistenceContext
    private EntityManager em;

    public Writers(DataSource ds, PlatformTransactionManager manager,
                   GroupRepository groupRepo, CouponRepository couponRepo, BenefitRepository benefitRepo,
                   @Value("${WRITE_BATCH_SIZE:100}") int writeBatch) {
        if (writeBatch < 1) throw new IllegalArgumentException("WRITE_BATCH_SIZE must be positive");
        this.ds = ds;
        this.writeBatch = writeBatch;
        this.groupRepo = groupRepo;
        this.couponRepo = couponRepo;
        this.benefitRepo = benefitRepo;
        transactions = new TransactionTemplate(manager);
        var config = new org.apache.ibatis.session.Configuration(
                new Environment("lab", new JdbcTransactionFactory(), ds));
        config.setCacheEnabled(false);
        config.addMapper(Upserts.class);
        mybatis = new SqlSessionFactoryBuilder().build(config);
    }

    /**
     * Returns after COMMIT; any failure rolls back this entire user batch.
     */
    public void write(String method, Rows rows) throws Exception {
        switch (method) {
            case "jdbc" -> jdbc(rows);
            case "mybatis" -> mybatis(rows);
            case "jpa_repository" -> transactions.executeWithoutResult(status -> jpa(rows, true));
            case "entity_manager" -> transactions.executeWithoutResult(status -> jpa(rows, false));
            default -> throw new IllegalArgumentException("Unknown method: " + method);
        }
    }

    private void jpa(Rows rows, boolean repository) {
        jpaBatch(rows.groups(), GroupEntity::new, GroupEntity::updateFrom, GroupEntity.class,
                groupRepo, groupRepo::findExisting, repository);
        jpaBatch(rows.coupons(), CouponEntity::new, CouponEntity::updateFrom, CouponEntity.class,
                couponRepo, couponRepo::findExisting, repository);
        jpaBatch(rows.benefits(), BenefitEntity::new, BenefitEntity::updateFrom, BenefitEntity.class,
                benefitRepo, benefitRepo::findExisting, repository);
    }

    private <R, E extends Persistable<RowId>> void jpaBatch(List<R> rows, Function<R, E> factory,
                                                            BiPredicate<E, R> update, Class<E> type, JpaRepository<E, RowId> repo,
                                                            Function<List<RowId>, List<E>> lookup, boolean repository) {
        for (int from = 0; from < rows.size(); from += writeBatch) {
            var chunk = rows.subList(from, Math.min(from + writeBatch, rows.size()));
            var candidates = chunk.stream().map(factory).toList();
            var ids = candidates.stream().map(Persistable::getId).toList();
            List<E> loaded = repository ? lookup.apply(ids) : em.createQuery(
                            "select e from " + type.getSimpleName() + " e where e.id in :ids", type)
                    .setParameter("ids", ids).getResultList();
            var existing = new HashMap<RowId, E>();
            for (E entity : loaded) existing.put(entity.getId(), entity);
            var changed = new ArrayList<E>();
            for (int i = 0; i < chunk.size(); i++) {
                E candidate = candidates.get(i);
                E entity = existing.get(candidate.getId());
                if (entity == null) {
                    if (repository) changed.add(candidate);
                    else em.persist(candidate);
                } else if (update.test(entity, chunk.get(i)) && repository) {
                    changed.add(entity);
                }
            }
            if (repository) {
                if (!changed.isEmpty()) repo.saveAll(changed);
                repo.flush();
            } else em.flush();
            // @DynamicUpdate writes only dirty columns; unchanged entities generate no UPDATE.
            em.clear();
        }
    }

    private void jdbc(Rows rows) throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                jdbcBatch(c, GROUP, rows.groups().stream().map(SqlUpsert::target).toList());
                jdbcBatch(c, COUPON, rows.coupons().stream().map(SqlUpsert::target).toList());
                jdbcBatch(c, BENEFIT, rows.benefits().stream().map(SqlUpsert::target).toList());
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    private void jdbcBatch(Connection c, Table table, List<Target> rows) throws SQLException {
        for (int from = 0; from < rows.size(); from += writeBatch) {
            var chunk = rows.subList(from, Math.min(from + writeBatch, rows.size()));
            var existing = jdbcExisting(c, new Selection(table, chunk));
            // Different dirty-column sets need different SQL shapes; each shape is batched.
            var commands = new LinkedHashMap<String, List<Command>>();
            for (Target row : chunk) {
                Command command = plan(table, row, existing.get(row.id()));
                if (command != null) commands.computeIfAbsent(command.jdbcSql(), key -> new ArrayList<>()).add(command);
            }
            for (var entry : commands.entrySet()) {
                try (PreparedStatement p = c.prepareStatement(entry.getKey())) {
                    for (Command command : entry.getValue()) {
                        for (int i = 0; i < command.values().size(); i++) {
                            Object value = command.values().get(i);
                            if (value == null) p.setNull(i + 1, command.types().get(i).getVendorTypeNumber());
                            else p.setObject(i + 1, value);
                        }
                        p.addBatch();
                    }
                    p.executeBatch();
                }
            }
        }
    }

    private Map<RowId, Map<String, Object>> jdbcExisting(Connection c, Selection selection) throws SQLException {
        var existing = new HashMap<RowId, Map<String, Object>>();
        try (PreparedStatement p = c.prepareStatement(jdbcSelect(selection))) {
            int index = 1;
            for (Target row : selection.rows()) {
                p.setString(index++, row.id().userId);
                p.setString(index++, row.id().itemId);
            }
            try (ResultSet result = p.executeQuery()) {
                while (result.next()) {
                    var values = new HashMap<String, Object>();
                    values.put("user_id", result.getString("user_id"));
                    values.put(selection.table().itemKey(), result.getString(selection.table().itemKey()));
                    for (Column column : selection.table().columns()) {
                        Object value = column.type() == JDBCType.TIMESTAMP_WITH_TIMEZONE
                                ? result.getObject(column.name(), java.time.OffsetDateTime.class)
                                : result.getObject(column.name());
                        values.put(column.name(), value);
                    }
                    existing.put(key(selection.table(), values), values);
                }
            }
        }
        return existing;
    }

    private void mybatis(Rows rows) {
        try (SqlSession session = mybatis.openSession(ExecutorType.BATCH, false)) {
            try {
                Upserts mapper = session.getMapper(Upserts.class);
                mappedBatch(session, mapper, GROUP, rows.groups().stream().map(SqlUpsert::target).toList());
                mappedBatch(session, mapper, COUPON, rows.coupons().stream().map(SqlUpsert::target).toList());
                mappedBatch(session, mapper, BENEFIT, rows.benefits().stream().map(SqlUpsert::target).toList());
                session.commit();
            } catch (RuntimeException ex) {
                session.rollback();
                throw ex;
            }
        }
    }

    private void mappedBatch(SqlSession session, Upserts mapper, Table table, List<Target> rows) {
        for (int from = 0; from < rows.size(); from += writeBatch) {
            var chunk = rows.subList(from, Math.min(from + writeBatch, rows.size()));
            var existing = new HashMap<RowId, Map<String, Object>>();
            for (var row : mapper.findExisting(new Selection(table, chunk))) existing.put(key(table, row), row);
            for (Target row : chunk) {
                Command command = plan(table, row, existing.get(row.id()));
                if (command != null) mapper.write(command);
            }
            session.flushStatements();
        }
    }

    public interface Upserts {
        @SelectProvider(type = SqlUpsert.class, method = "select")
        List<Map<String, Object>> findExisting(Selection selection);

        @UpdateProvider(type = SqlUpsert.class, method = "command")
        void write(Command command);
    }
}
