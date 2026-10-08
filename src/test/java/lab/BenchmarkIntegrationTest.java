package lab;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static lab.Model.*;

/** Run explicitly with RUN_DB_TESTS=true mvn test against the dedicated Docker lab. */
@SpringBootTest(properties={"bench.enabled=false"})
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
class BenchmarkIntegrationTest {
    @ParameterizedTest @ValueSource(strings={"jdbc","mybatis","jpa_repository","entity_manager"})
    void unchangedRowsAreNotUpdatedAndOnlyDirtyColumnsAppearInUpdate(String method) throws Exception {
        var rows=transformer.transform(service.read("",1));
        writers.write(method,rows);
        var db=new JdbcTemplate(ds);
        var c=rows.coupons().get(0);
        String before=versions(db);
        writers.write(method,rows);
        assertEquals(before,versions(db),"Identical input must not produce new PostgreSQL row versions");
        // UPDATE OF fires if an unchanged column appears in SET, even if its value stays equal.
        db.execute("CREATE OR REPLACE FUNCTION lab_java.reject_unchanged_columns() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'unchanged column was included in UPDATE'; END $$");
        db.execute("CREATE TRIGGER reject_unchanged_columns BEFORE UPDATE OF reward_group_id,coupon_code,usage_daily,usage_weekly,usage_monthly,update_date ON lab_java.user_coupon FOR EACH ROW EXECUTE FUNCTION lab_java.reject_unchanged_columns()");
        db.execute("CREATE TRIGGER reject_unchanged_columns BEFORE UPDATE OF period_start,period_end ON lab_java.user_reward_group FOR EACH ROW EXECUTE FUNCTION lab_java.reject_unchanged_columns()");
        db.execute("CREATE TRIGGER reject_unchanged_columns BEFORE UPDATE OF end_date,last_update ON lab_java.user_benefit FOR EACH ROW EXECUTE FUNCTION lab_java.reject_unchanged_columns()");
        try {
            var changed=new CouponRow(c.userId(),c.couponId(),c.rewardGroupId(),c.couponCode(),99,
                c.usageDaily(),c.usageWeekly(),c.usageMonthly(),c.updateDate());
            var g=rows.groups().get(0);
            var b=rows.benefits().get(0);
            writers.write(method,new Rows(
                java.util.List.of(new GroupRow(g.userId(),g.groupId(),"changed",g.periodStart(),g.periodEnd())),
                java.util.List.of(changed),
                java.util.List.of(new BenefitRow(b.userId(),b.periodType(),new java.math.BigDecimal("42.50"),b.endDate(),b.lastUpdate()))));
            assertEquals(99,db.queryForObject("SELECT quota_used FROM lab_java.user_coupon WHERE user_id=? AND coupon_id=?",
                Integer.class,c.userId(),c.couponId()));
        } finally {
            db.execute("DROP TRIGGER reject_unchanged_columns ON lab_java.user_coupon");
            db.execute("DROP TRIGGER reject_unchanged_columns ON lab_java.user_reward_group");
            db.execute("DROP TRIGGER reject_unchanged_columns ON lab_java.user_benefit");
            db.execute("DROP FUNCTION lab_java.reject_unchanged_columns()");
        }
    }

    @ParameterizedTest @ValueSource(strings={"jdbc","mybatis","jpa_repository","entity_manager"})
    void equivalentOffsetsAndDecimalScalesDoNotTriggerUpdates(String method) throws Exception {
        var rows=transformer.transform(service.read("",1));
        writers.write(method,rows);
        String before=service.targetVersions();
        var equivalent=new Rows(
            rows.groups().stream().map(r->new GroupRow(r.userId(),r.groupId(),r.fileName(),
                r.periodStart().withOffsetSameInstant(java.time.ZoneOffset.UTC),r.periodEnd().withOffsetSameInstant(java.time.ZoneOffset.UTC))).toList(),
            rows.coupons().stream().map(r->new CouponRow(r.userId(),r.couponId(),r.rewardGroupId(),r.couponCode(),
                r.quotaUsed(),r.usageDaily(),r.usageWeekly(),r.usageMonthly(),r.updateDate()==null ? null : r.updateDate().withOffsetSameInstant(java.time.ZoneOffset.UTC))).toList(),
            rows.benefits().stream().map(r->new BenefitRow(r.userId(),r.periodType(),r.amount().setScale(3),
                r.endDate().withOffsetSameInstant(java.time.ZoneOffset.UTC),r.lastUpdate().withOffsetSameInstant(java.time.ZoneOffset.UTC))).toList());
        writers.write(method,equivalent);
        assertEquals(before,service.targetVersions());
    }

    private String versions(JdbcTemplate db) {
        return db.queryForObject("SELECT string_agg(v,',' ORDER BY v) FROM ("
            +"SELECT 'g:'||user_id||':'||reward_group_id||':'||xmin::text AS v FROM lab_java.user_reward_group UNION ALL "
            +"SELECT 'c:'||user_id||':'||coupon_id||':'||xmin::text FROM lab_java.user_coupon UNION ALL "
            +"SELECT 'b:'||user_id||':'||period_type||':'||xmin::text FROM lab_java.user_benefit) rows",String.class);
    }

    @org.junit.jupiter.api.Test
    void unchangedScenarioCanBeMigratedWithoutPhysicalUpdates() throws Exception {
        service.prepareTrial("unchanged",1000);
        var db=new JdbcTemplate(ds);
        String before=versions(db);
        service.migrate("jdbc",1000);
        service.validate(1000);
        assertEquals(before,versions(db));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("scenarioCases")
    void eachScenarioHasIndependentBaselineAndCorrectResults(String method,String scenario) throws Exception {
        service.prepareTrial(scenario,3);
        var db=new JdbcTemplate(ds);
        long existing=switch (scenario) { case "insert" -> 0; case "mixed" -> 1; default -> 3; };
        assertEquals(existing*6,db.queryForObject("SELECT count(*) FROM lab_java.user_reward_group",Long.class));
        assertEquals(existing*10,db.queryForObject("SELECT count(*) FROM lab_java.user_coupon",Long.class));
        assertEquals(existing*3,db.queryForObject("SELECT count(*) FROM lab_java.user_benefit",Long.class));
        String before=service.targetVersions();
        var migration=service.migrate(method,3);
        service.validate(3);
        service.validateTargetCounts(migration);
        if (scenario.equals("unchanged")) assertEquals(before,service.targetVersions());
        else assertNotEquals(before,service.targetVersions());
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> scenarioCases() {
        return Writers.METHODS.stream().flatMap(method->BenchmarkService.SCENARIOS.stream()
            .map(scenario->org.junit.jupiter.params.provider.Arguments.of(method,scenario)));
    }

    @ParameterizedTest @ValueSource(strings={"jdbc","mybatis","jpa_repository","entity_manager"})
    void allNonKeyColumnsCanFollowChangedSource(String method) throws Exception {
        writers.write(method,transformer.transform(service.read("",1)));
        var db=new JdbcTemplate(ds);
        db.update("UPDATE lab_java.m_user SET \"group\"=(SELECT jsonb_object_agg(e.key,e.value||jsonb_build_object('fileName','changed','periodStart','2027-01-01T00:00:00+07:00','periodEnd','2027-02-01T00:00:00+07:00')) FROM jsonb_each(\"group\") e),"
            +"coupons=(SELECT jsonb_object_agg(e.key,e.value||jsonb_build_object('rewardGroupId','CHANGED_GROUP','couponCode','changed','quotaUsed',99,'usageDaily',98,'usageWeekly',97,'usageMonthly',96,'updateDate','2027-01-02T00:00:00+07:00')) FROM jsonb_each(coupons) e),"
            +"benefit=(SELECT jsonb_object_agg(e.key,e.value||jsonb_build_object('amount',42.50,'endDate','2027-02-01T00:00:00+07:00','lastUpdate','2027-01-02T00:00:00+07:00')) FROM jsonb_each(benefit) e) WHERE id='U00000001'");
        var result=service.migrate(method,1);
        service.validate(1);
        service.validateTargetCounts(result);
    }

    @ParameterizedTest @ValueSource(strings={"jdbc","mybatis","jpa_repository","entity_manager"})
    void itemsRemovedFromSourceRemainUntouchedInTarget(String method) throws Exception {
        var rows=transformer.transform(service.read("",1));
        writers.write(method,rows);
        var db=new JdbcTemplate(ds);
        String before=service.targetVersions();
        db.update("UPDATE lab_java.m_user SET \"group\"='{}'::jsonb,benefit='{}'::jsonb,coupons=coupons-? WHERE id=?",
            rows.coupons().get(0).couponId(),"U00000001");
        service.migrate(method,1);
        service.validate(1); // Validates present source keys, allowing retained items.
        assertEquals(before,service.targetVersions());
        assertEquals(6L,db.queryForObject("SELECT count(*) FROM lab_java.user_reward_group",Long.class));
        assertEquals(10L,db.queryForObject("SELECT count(*) FROM lab_java.user_coupon",Long.class));
        assertEquals(3L,db.queryForObject("SELECT count(*) FROM lab_java.user_benefit",Long.class));
    }

    @Autowired BenchmarkService service;
    @Autowired Transformer transformer;
    @Autowired Writers writers;
    @Autowired DataSource ds;
    @BeforeEach void seed() throws Exception { service.setup(1000); }

    @ParameterizedTest @ValueSource(strings={"jdbc","mybatis","jpa_repository","entity_manager"})
    void fullMigrationPreservesAllValuesAndValidatorDetectsCorruption(String method) throws Exception {
        var result=service.migrate(method,1000);
        assertEquals(6000,result.groups()); assertEquals(10000,result.coupons()); assertEquals(3000,result.benefits());
        assertEquals(2,result.batches().size());
        service.validate(1000);
        var db=new JdbcTemplate(ds);
        db.update("UPDATE lab_java.user_coupon SET quota_used=999 WHERE user_id='U00000001'");
        assertThrows(IllegalStateException.class,()->service.validate(1000));
    }
    @ParameterizedTest @ValueSource(strings={"jdbc","mybatis","jpa_repository","entity_manager"})
    void upsertUpdatesChangedValuesAndInsertsMissingKeys(String method) throws Exception {
        var original=transformer.transform(service.read("",1));
        writers.write(method,original);
        var g=original.groups().get(0);
        var c=original.coupons().stream().filter(row->row.updateDate()!=null).findFirst().orElseThrow();
        var b=original.benefits().get(0);
        var changed=new Rows(
            java.util.List.of(new GroupRow(g.userId(),g.groupId(),null,g.periodStart(),g.periodEnd()),
                new GroupRow(g.userId(),"NEW_GROUP","new",g.periodStart(),g.periodEnd())),
            java.util.List.of(new CouponRow(c.userId(),c.couponId(),c.rewardGroupId(),c.couponCode(),99,
                c.usageDaily(),c.usageWeekly(),c.usageMonthly(),null),
                new CouponRow(c.userId(),"NEW_COUPON",c.rewardGroupId(),"new",0,0,0,0,null)),
            java.util.List.of(new BenefitRow(b.userId(),b.periodType(),new java.math.BigDecimal("42.50"),
                b.endDate(),b.lastUpdate())));
        writers.write(method,changed);
        var db=new JdbcTemplate(ds);
        assertNull(db.queryForObject("SELECT file_name FROM lab_java.user_reward_group WHERE user_id=? AND reward_group_id=?",
            String.class,g.userId(),g.groupId()));
        assertEquals(99,db.queryForObject("SELECT quota_used FROM lab_java.user_coupon WHERE user_id=? AND coupon_id=?",
            Integer.class,c.userId(),c.couponId()));
        assertNull(db.queryForObject("SELECT update_date FROM lab_java.user_coupon WHERE user_id=? AND coupon_id=?",
            java.sql.Timestamp.class,c.userId(),c.couponId()));
        assertEquals(new java.math.BigDecimal("42.50"),db.queryForObject("SELECT amount FROM lab_java.user_benefit WHERE user_id=? AND period_type=?",
            java.math.BigDecimal.class,b.userId(),b.periodType()));
        // Only one old item was supplied: absent items must remain, not be deleted.
        assertEquals(7L,db.queryForObject("SELECT count(*) FROM lab_java.user_reward_group",Long.class));
        assertEquals(11L,db.queryForObject("SELECT count(*) FROM lab_java.user_coupon",Long.class));
        assertEquals(3L,db.queryForObject("SELECT count(*) FROM lab_java.user_benefit",Long.class));
    }

    @ParameterizedTest @ValueSource(strings={"jdbc","mybatis","jpa_repository","entity_manager"})
    void invalidForeignKeyRollsBackUpdatesAndInsertsInSameBatch(String method) throws Exception {
        var db=new JdbcTemplate(ds);
        // Keep one old coupon, then force failure after groups and its update were attempted.
        var source=service.read("",1);
        Rows rows=transformer.transform(source);
        var coupon=rows.coupons().get(0);
        db.update("INSERT INTO lab_java.user_coupon (user_id,coupon_id,reward_group_id,coupon_code,quota_used,usage_daily,usage_weekly,usage_monthly,update_date) VALUES (?,?,?,?,?,?,?,?,?)",
            coupon.userId(),coupon.couponId(),coupon.rewardGroupId(),coupon.couponCode(),coupon.quotaUsed(),coupon.usageDaily(),coupon.usageWeekly(),coupon.usageMonthly(),coupon.updateDate());
        var changed=new CouponRow(coupon.userId(),coupon.couponId(),coupon.rewardGroupId(),coupon.couponCode(),99,
            coupon.usageDaily(),coupon.usageWeekly(),coupon.usageMonthly(),coupon.updateDate());
        var invalid=new CouponRow("MISSING_USER","INVALID",coupon.rewardGroupId(),"invalid",0,0,0,0,null);
        var failing=new Rows(rows.groups(),java.util.List.of(changed,invalid),rows.benefits());
        assertThrows(Exception.class,()->writers.write(method,failing));
        assertEquals(coupon.quotaUsed(),db.queryForObject("SELECT quota_used FROM lab_java.user_coupon WHERE user_id=? AND coupon_id=?",
            Integer.class,coupon.userId(),coupon.couponId()));
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM lab_java.user_reward_group",Long.class));
        assertEquals(1L,db.queryForObject("SELECT count(*) FROM lab_java.user_coupon",Long.class));
        assertEquals(0L,db.queryForObject("SELECT count(*) FROM lab_java.user_benefit",Long.class));
    }
}
