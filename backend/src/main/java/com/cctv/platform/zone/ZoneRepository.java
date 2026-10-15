package com.cctv.platform.zone;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ZoneRepository extends JpaRepository<Zone, Long> {

    List<Zone> findByCameraIdOrderById(long cameraId);
}
