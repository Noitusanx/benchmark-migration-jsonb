package lab;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.math.BigDecimal;

import org.springframework.data.domain.Persistable;

import static lab.Model.*;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "user_coupon", schema = "lab_java")
public class CouponEntity implements Persistable<RowId> {
    @EmbeddedId
    @AttributeOverride(name = "itemId", column = @Column(name = "coupon_id"))
    public RowId id;
    @Column(name = "reward_group_id")
    public String rewardGroupId;
    @Column(name = "coupon_code")
    public String couponCode;
    @Column(name = "quota_used")
    public int quotaUsed;
    @Column(name = "usage_daily")
    public int usageDaily;
    @Column(name = "usage_weekly")
    public int usageWeekly;
    @Column(name = "usage_monthly")
    public int usageMonthly;
    @Column(name = "update_date")
    public OffsetDateTime updateDate;
    // Missing keys are new; existing rows are loaded before their values are changed.
    @Transient
    private boolean newEntity = true;

    public CouponEntity() {
    }

    public CouponEntity(CouponRow r) {
        id = new RowId(r.userId(), r.couponId());
        rewardGroupId = r.rewardGroupId();
        couponCode = r.couponCode();
        quotaUsed = r.quotaUsed();
        usageDaily = r.usageDaily();
        usageWeekly = r.usageWeekly();
        usageMonthly = r.usageMonthly();
        updateDate = r.updateDate();
    }

    public boolean updateFrom(CouponRow r) {
        boolean changed = false;
        if (!SqlUpsert.same(rewardGroupId, r.rewardGroupId())) {
            rewardGroupId = r.rewardGroupId();
            changed = true;
        }
        if (!SqlUpsert.same(couponCode, r.couponCode())) {
            couponCode = r.couponCode();
            changed = true;
        }
        if (quotaUsed != r.quotaUsed()) {
            quotaUsed = r.quotaUsed();
            changed = true;
        }
        if (usageDaily != r.usageDaily()) {
            usageDaily = r.usageDaily();
            changed = true;
        }
        if (usageWeekly != r.usageWeekly()) {
            usageWeekly = r.usageWeekly();
            changed = true;
        }
        if (usageMonthly != r.usageMonthly()) {
            usageMonthly = r.usageMonthly();
            changed = true;
        }
        if (!SqlUpsert.same(updateDate, r.updateDate())) {
            updateDate = r.updateDate();
            changed = true;
        }
        return changed;
    }

    @Override
    public RowId getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    public void persisted() {
        newEntity = false;
    }
}
