package lab;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static lab.Model.*;

class TransformerTest {
    private final Transformer transformer=new Transformer();
    private static final String GROUP="""
        {"NAMED_GROUP":{"fileName":null,"periodStart":"2026-03-01T00:00:00+07:00","periodEnd":"9999-01-01T00:00:00+07:00"}}
        """;
    private static final String COUPON="""
        {"C001":{"couponId":"C001","couponCode":"DUMMY","rewardGroupId":"NAMED_GROUP","quotaUsed":4,"usageDaily":1,"usageWeekly":2,"usageMonthly":3}}
        """;
    private static final String BENEFIT="""
        {"daily":{"amount":0.10,"endDate":"2026-03-31T23:59:59.999+07:00","lastUpdate":"2026-03-02T16:33:02.123+07:00"}}
        """;
    @Test void preservesKeysCountersNullsDecimalAndTimestamp() {
        var rows=transformer.transform(List.of(new Source("U1",GROUP,COUPON,BENEFIT)));
        assertEquals("NAMED_GROUP",rows.groups().get(0).groupId());
        assertNull(rows.groups().get(0).fileName());
        assertEquals(9999,rows.groups().get(0).periodEnd().getYear());
        assertEquals("U1",rows.coupons().get(0).userId());
        assertEquals(4,rows.coupons().get(0).quotaUsed());
        assertEquals(3,rows.coupons().get(0).usageMonthly());
        assertNull(rows.coupons().get(0).updateDate());
        assertEquals("0.10",rows.benefits().get(0).amount().toPlainString());
        assertEquals("2026-03-02T09:33:02.123Z",rows.benefits().get(0).lastUpdate().toInstant().toString());
    }
    @Test void emptyObjectsProduceNoRows() {
        var rows=transformer.transform(List.of(new Source("U1","{}","{}","{}")));
        assertTrue(rows.groups().isEmpty()); assertTrue(rows.coupons().isEmpty()); assertTrue(rows.benefits().isEmpty());
    }
    @Test void couponKeyMismatchFailsInsteadOfSilentlyChangingIdentity() {
        assertThrows(IllegalArgumentException.class,()->transformer.transform(
            List.of(new Source("U1","{}",COUPON.replace("\"couponId\":\"C001\"","\"couponId\":\"C002\""),"{}"))));
    }
    @Test void malformedJsonAndWrongShapeFailWithUserId() {
        var error=assertThrows(IllegalArgumentException.class,()->transformer.transform(
            List.of(new Source("U1","{}","{\"broken\":","{}"))));
        assertTrue(error.getMessage().contains("U1"));
        assertThrows(IllegalArgumentException.class,()->transformer.transform(List.of(new Source("U1","[]","{}","{}"))));
    }
    @Test void countersAreNotSilentlyTruncatedOrDefaulted() {
        assertThrows(IllegalArgumentException.class,()->transformer.transform(List.of(
            new Source("U1","{}",COUPON.replace("\"quotaUsed\":4","\"quotaUsed\":1.5"),"{}"))));
        assertThrows(IllegalArgumentException.class,()->transformer.transform(List.of(
            new Source("U1","{}",COUPON.replace("\"quotaUsed\":4,",""),"{}"))));
    }
    @Test void moneyIsNotSilentlyRounded() {
        assertThrows(IllegalArgumentException.class,()->transformer.transform(List.of(
            new Source("U1","{}","{}",BENEFIT.replace("0.10","0.105")))));
    }
    @Test void duplicateJsonKeysFailInsteadOfDroppingAnEntry() {
        String duplicate="{\"C001\":"+COUPON.substring(COUPON.indexOf(':')+1,COUPON.lastIndexOf('}'))
            +",\"C001\":"+COUPON.substring(COUPON.indexOf(':')+1,COUPON.lastIndexOf('}'))+"}";
        assertThrows(IllegalArgumentException.class,()->transformer.transform(
            List.of(new Source("U1","{}",duplicate,"{}"))));
    }
}
