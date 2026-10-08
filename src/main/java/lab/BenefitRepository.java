package lab;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BenefitRepository extends JpaRepository<BenefitEntity, RowId> {
    @org.springframework.data.jpa.repository.Query("select e from BenefitEntity e where e.id in :ids")
    java.util.List<BenefitEntity> findExisting(@org.springframework.data.repository.query.Param("ids") java.util.List<RowId> ids);
}
