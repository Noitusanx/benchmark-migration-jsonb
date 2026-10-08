-- Run AFTER a benchmark. The application creates and seeds the four lab tables.
SELECT current_database(), current_schema();
SELECT id, account_no, "group", coupons, benefit
FROM lab_java.m_user WHERE id='U00000001';
SELECT * FROM lab_java.user_reward_group WHERE user_id='U00000001' ORDER BY reward_group_id;
SELECT * FROM lab_java.user_coupon WHERE user_id='U00000001' ORDER BY coupon_id;
SELECT * FROM lab_java.user_benefit WHERE user_id='U00000001' ORDER BY period_type;
