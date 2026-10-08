-- Dedicated lab only. These four tables have no dependency on m_coupon.
CREATE SCHEMA IF NOT EXISTS lab_java;
CREATE TABLE IF NOT EXISTS lab_java.m_user (
    id varchar(64) PRIMARY KEY,
    account_no varchar(64) NOT NULL,
    "group" jsonb NOT NULL CHECK (jsonb_typeof("group") = 'object'),
    coupons jsonb NOT NULL CHECK (jsonb_typeof(coupons) = 'object'),
    benefit jsonb NOT NULL CHECK (jsonb_typeof(benefit) = 'object')
);
CREATE TABLE IF NOT EXISTS lab_java.user_reward_group (
    user_id varchar(64) NOT NULL REFERENCES lab_java.m_user(id),
    reward_group_id varchar(255) NOT NULL,
    file_name text,
    period_start timestamptz NOT NULL,
    period_end timestamptz NOT NULL,
    PRIMARY KEY (user_id, reward_group_id)
);
CREATE TABLE IF NOT EXISTS lab_java.user_coupon (
    user_id varchar(64) NOT NULL REFERENCES lab_java.m_user(id),
    coupon_id varchar(255) NOT NULL,
    reward_group_id varchar(255) NOT NULL,
    coupon_code varchar(255) NOT NULL,
    quota_used integer NOT NULL,
    usage_daily integer NOT NULL,
    usage_weekly integer NOT NULL,
    usage_monthly integer NOT NULL,
    update_date timestamptz,
    PRIMARY KEY (user_id, coupon_id)
);
CREATE TABLE IF NOT EXISTS lab_java.user_benefit (
    user_id varchar(64) NOT NULL REFERENCES lab_java.m_user(id),
    period_type varchar(16) NOT NULL CHECK (period_type IN ('daily','weekly','monthly')),
    amount numeric(19,2) NOT NULL,
    end_date timestamptz NOT NULL,
    last_update timestamptz NOT NULL,
    PRIMARY KEY (user_id, period_type)
);
