package lab;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRepository extends JpaRepository<CouponEntity, RowId> {
    @org.springframework.data.jpa.repository.Query("select e from CouponEntity e where e.id in :ids")
    java.util.List<CouponEntity> findExisting(@org.springframework.data.repository.query.Param("ids") java.util.List<RowId> ids);
}
