package lab;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.math.BigDecimal;

import org.springframework.data.domain.Persistable;

import static lab.Model.*;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "user_reward_group", schema = "lab_java")
public class GroupEntity implements Persistable<RowId> {
    @EmbeddedId
    @AttributeOverride(name = "itemId", column = @Column(name = "reward_group_id"))
    public RowId id;
    @Column(name = "file_name")
    public String fileName;
    @Column(name = "period_start")
    public OffsetDateTime periodStart;
    @Column(name = "period_end")
    public OffsetDateTime periodEnd;
    // Missing keys are new; existing rows are loaded before their values are changed.
    @Transient
    private boolean newEntity = true;

    public GroupEntity() {
    }

    public GroupEntity(GroupRow r) {
        id = new RowId(r.userId(), r.groupId());
        fileName = r.fileName();
        periodStart = r.periodStart();
        periodEnd = r.periodEnd();
    }

    public boolean updateFrom(GroupRow r) {
        boolean changed = false;
        if (!SqlUpsert.same(fileName, r.fileName())) {
            fileName = r.fileName();
            changed = true;
        }
        if (!SqlUpsert.same(periodStart, r.periodStart())) {
            periodStart = r.periodStart();
            changed = true;
        }
        if (!SqlUpsert.same(periodEnd, r.periodEnd())) {
            periodEnd = r.periodEnd();
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
