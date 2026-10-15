package com.cctv.platform.camera;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CameraRepository extends JpaRepository<Camera, Long> {

    @Query("SELECT c FROM User u JOIN u.cameras c WHERE u.id = :userId ORDER BY c.id")
    List<Camera> findAccessibleBy(long userId);

    @Query("SELECT COUNT(u) > 0 FROM User u JOIN u.cameras c WHERE u.id = :userId AND c.id = :cameraId")
    boolean isAccessibleBy(long userId, long cameraId);
}
