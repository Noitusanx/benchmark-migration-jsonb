package lab;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class Model {
    private Model() {
    }

    public record Source(String id, String groups, String coupons, String benefit) {
    }

    public record GroupRow(String userId, String groupId, String fileName,
                           OffsetDateTime periodStart, OffsetDateTime periodEnd) {
    }

    public record CouponRow(String userId, String couponId, String rewardGroupId,
                            String couponCode, int quotaUsed, int usageDaily,
                            int usageWeekly, int usageMonthly, OffsetDateTime updateDate) {
    }

    public record BenefitRow(String userId, String periodType, BigDecimal amount,
                             OffsetDateTime endDate, OffsetDateTime lastUpdate) {
    }

    public record Rows(List<GroupRow> groups, List<CouponRow> coupons, List<BenefitRow> benefits) {
    }
}
