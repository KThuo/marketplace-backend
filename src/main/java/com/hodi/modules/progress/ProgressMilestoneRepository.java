package com.hodi.modules.progress;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProgressMilestoneRepository extends JpaRepository<ProgressMilestone, Long> {

    /** In build order, live only. What both the editor's picker and the public timeline's legend read. */
    @Query("select m from ProgressMilestone m where m.status <> 5 order by m.sortOrder, m.id")
    List<ProgressMilestone> findLive();

    @Query("select m from ProgressMilestone m where m.code = :code and m.status <> 5")
    Optional<ProgressMilestone> findLiveByCode(String code);
}
