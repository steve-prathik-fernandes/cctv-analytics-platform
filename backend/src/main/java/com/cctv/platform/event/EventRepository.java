package com.cctv.platform.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event> {

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Event e WHERE e.jobId = :jobId")
    int deleteByJobId(long jobId);

    List<Event> findByJobIdOrderByStartedAt(long jobId);
}
