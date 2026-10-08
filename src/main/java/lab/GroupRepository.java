package lab;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GroupRepository extends JpaRepository<GroupEntity, RowId> {
    @org.springframework.data.jpa.repository.Query("select e from GroupEntity e where e.id in :ids")
    java.util.List<GroupEntity> findExisting(@org.springframework.data.repository.query.Param("ids") java.util.List<RowId> ids);
}
