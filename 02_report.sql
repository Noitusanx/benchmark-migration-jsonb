-- Tables retain only the LAST trial. All trials' timings are in results/*.csv.
SELECT
    (SELECT count(*) FROM lab_java.m_user) AS source_users,
    (SELECT count(*) FROM lab_java.user_reward_group) AS group_rows,
    (SELECT count(*) FROM lab_java.user_coupon) AS coupon_rows,
    (SELECT count(*) FROM lab_java.user_benefit) AS benefit_rows;

WITH expected AS (
    SELECT 'groups' AS type, count(*) AS rows FROM lab_java.m_user u CROSS JOIN LATERAL jsonb_each(u."group") e
    UNION ALL
    SELECT 'coupons',count(*) FROM lab_java.m_user u CROSS JOIN LATERAL jsonb_each(u.coupons) e
    UNION ALL
    SELECT 'benefits',count(*) FROM lab_java.m_user u CROSS JOIN LATERAL jsonb_each(u.benefit) e
), actual AS (
    SELECT 'groups' AS type,count(*) AS rows FROM lab_java.user_reward_group
    UNION ALL SELECT 'coupons',count(*) FROM lab_java.user_coupon
    UNION ALL SELECT 'benefits',count(*) FROM lab_java.user_benefit
)
SELECT e.type,e.rows AS expected_rows,a.rows AS actual_rows,e.rows=a.rows AS counts_match
FROM expected e JOIN actual a USING(type) ORDER BY e.type;

-- Missing updateDate in JSON should remain SQL NULL.
SELECT coupon_code,update_date FROM lab_java.user_coupon
WHERE user_id='U00000001' ORDER BY coupon_code;
