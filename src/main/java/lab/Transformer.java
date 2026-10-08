package lab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import static lab.Model.*;

/**
 * Shared by all four writers: only the database writing strategy differs.
 */
@Component
public class Transformer {
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public Rows transform(List<Source> users) {
        var groups = new ArrayList<GroupRow>();
        var coupons = new ArrayList<CouponRow>();
        var benefits = new ArrayList<BenefitRow>();
        for (Source user : users) {
            try {
                object(user.groups()).fields().forEachRemaining(e -> {
                    var v = item(e.getValue());
                    groups.add(new GroupRow(user.id(), e.getKey(), optionalText(v, "fileName"),
                            date(v, "periodStart"), date(v, "periodEnd")));
                });
                object(user.coupons()).fields().forEachRemaining(e -> {
                    var v = item(e.getValue());
                    String couponId = text(v, "couponId");
                    if (!couponId.equals(e.getKey()))
                        throw new IllegalArgumentException("couponId differs from JSON key: " + e.getKey());
                    coupons.add(new CouponRow(user.id(), couponId, text(v, "rewardGroupId"),
                            text(v, "couponCode"), integer(v, "quotaUsed"), integer(v, "usageDaily"),
                            integer(v, "usageWeekly"), integer(v, "usageMonthly"),
                            v.hasNonNull("updateDate") ? date(v, "updateDate") : null));
                });
                object(user.benefit()).fields().forEachRemaining(e -> {
                    if (!Set.of("daily", "weekly", "monthly").contains(e.getKey()))
                        throw new IllegalArgumentException("Unknown benefit period: " + e.getKey());
                    var v = item(e.getValue());
                    JsonNode amount = v.get("amount");
                    if (amount == null || !amount.isNumber())
                        throw new IllegalArgumentException("amount must be numeric");
                    benefits.add(new BenefitRow(user.id(), e.getKey(),
                            amount.decimalValue().setScale(2, RoundingMode.UNNECESSARY),
                            date(v, "endDate"), date(v, "lastUpdate")));
                });
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid JSON for user " + user.id() + ": " + e.getMessage(), e);
            }
        }
        return new Rows(groups, coupons, benefits);
    }

    private JsonNode object(String value) throws Exception {
        if (value == null) throw new IllegalArgumentException("JSON is SQL NULL");
        return item(json.readTree(value));
    }

    private static JsonNode item(JsonNode value) {
        if (value == null || !value.isObject()) throw new IllegalArgumentException("Expected JSON object");
        return value;
    }

    private static String text(JsonNode v, String name) {
        var n = v.get(name);
        if (n == null || !n.isTextual() || n.asText().isBlank())
            throw new IllegalArgumentException(name + " must be nonblank text");
        return n.asText();
    }

    private static String optionalText(JsonNode v, String name) {
        if (!v.hasNonNull(name)) return null;
        var n = v.get(name);
        if (!n.isTextual()) throw new IllegalArgumentException(name + " must be text or null");
        return n.asText();
    }

    private static int integer(JsonNode v, String name) {
        var n = v.get(name);
        if (n == null || !n.isIntegralNumber() || !n.canConvertToInt())
            throw new IllegalArgumentException(name + " must be an integer within int range");
        return n.intValue();
    }

    private static OffsetDateTime date(JsonNode v, String name) {
        return OffsetDateTime.parse(text(v, name));
    }
}
