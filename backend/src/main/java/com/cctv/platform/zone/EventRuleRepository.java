package com.cctv.platform.zone;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRuleRepository extends JpaRepository<EventRule, Long> {

    List<EventRule> findByCameraIdOrderById(long cameraId);

    List<EventRule> findByCameraIdAndEnabledTrueOrderById(long cameraId);
}
