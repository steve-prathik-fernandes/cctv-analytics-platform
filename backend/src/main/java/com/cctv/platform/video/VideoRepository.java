package com.cctv.platform.video;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VideoRepository extends JpaRepository<Video, Long> {

    List<Video> findByCameraIdOrderById(long cameraId);

    boolean existsByCameraId(long cameraId);
}
