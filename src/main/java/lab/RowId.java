package lab;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class RowId implements Serializable {
    @Column(name = "user_id", nullable = false, length = 64)
    public String userId;
    @Column(name = "item_id", nullable = false, length = 255)
    public String itemId;

    public RowId() {
    }

    public RowId(String userId, String itemId) {
        this.userId = userId;
        this.itemId = itemId;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof RowId other && Objects.equals(userId, other.userId)
                && Objects.equals(itemId, other.itemId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, itemId);
    }
}
