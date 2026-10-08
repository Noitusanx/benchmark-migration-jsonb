-- Parameter ? is the number of SOURCE USERS, not the number of coupon items.
INSERT INTO lab_java.m_user (id, account_no, "group", coupons, benefit)
SELECT 'U' || lpad(u::text,8,'0'), 'DUMMY-' || lpad(u::text,8,'0'),
  (SELECT jsonb_object_agg(gid, jsonb_build_object(
      'fileName', CASE WHEN k=6 THEN NULL ELSE 'dummy.assign-group-' || k END,
      'periodStart','2026-03-01T00:00:00.000+07:00',
      'periodEnd',CASE WHEN k=6 THEN '9999-01-01T00:00:00.000+07:00'
                      ELSE '2026-03-31T23:59:59.999+07:00' END))
   FROM generate_series(1,6) k
   CROSS JOIN LATERAL (SELECT CASE WHEN k=6 THEN md5('group-6')::uuid::text
                                 ELSE 'DUMMY_GROUP_' || k END AS gid) ids),
  (SELECT jsonb_object_agg(cid,
      jsonb_build_object('couponId',cid,'couponCode','DUMMY_COUPON_' || k,
        'quotaUsed',u%5,'usageDaily',u%3,'usageWeekly',u%3+1,
        'usageMonthly',u%3+2,
        'rewardGroupId',CASE WHEN k%6=0 THEN md5('group-6')::uuid::text
                            ELSE 'DUMMY_GROUP_' || k%6 END)
      || CASE WHEN k%2=0 THEN '{}'::jsonb
              ELSE jsonb_build_object('updateDate','2026-03-02T09:41:48.921+07:00') END)
   FROM generate_series(1,10) k
   CROSS JOIN LATERAL (SELECT md5('coupon-' || k)::uuid::text AS cid) ids),
  (SELECT jsonb_object_agg(p, jsonb_build_object(
      'amount', (u%100)*0.01 + CASE p WHEN 'daily' THEN 0 WHEN 'weekly' THEN 1 ELSE 2 END,
      'endDate','2026-03-31T23:59:59.999+07:00',
      'lastUpdate','2026-03-02T16:33:02.123+07:00'))
   FROM (VALUES ('daily'),('weekly'),('monthly')) periods(p))
FROM generate_series(1, ?) AS users(u);
