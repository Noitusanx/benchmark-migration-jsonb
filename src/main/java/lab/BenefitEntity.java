package lab;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.math.BigDecimal;

import org.springframework.data.domain.Persistable;

import static lab.Model.*;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "user_benefit", schema = "lab_java")
public class BenefitEntity implements Persistable<RowId> {
    @EmbeddedId
    @AttributeOverride(name = "itemId", column = @Column(name = "period_type"))
    public RowId id;
    @Column(name = "amount", precision = 19, scale = 2)
    public BigDecimal amount;
    @Column(name = "end_date")
    public OffsetDateTime endDate;
    @Column(name = "last_update")
    public OffsetDateTime lastUpdate;
    // Missing keys are new; existing rows are loaded before their values are changed.
    @Transient
    private boolean newEntity = true;

    public BenefitEntity() {
    }

    public BenefitEntity(BenefitRow r) {
        id = new RowId(r.userId(), r.periodType());
        amount = r.amount();
        endDate = r.endDate();
        lastUpdate = r.lastUpdate();
    }

    public boolean updateFrom(BenefitRow r) {
        boolean changed = false;
        if (!SqlUpsert.same(amount, r.amount())) {
            amount = r.amount();
            changed = true;
        }
        if (!SqlUpsert.same(endDate, r.endDate())) {
            endDate = r.endDate();
            changed = true;
        }
        if (!SqlUpsert.same(lastUpdate, r.lastUpdate())) {
            lastUpdate = r.lastUpdate();
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
