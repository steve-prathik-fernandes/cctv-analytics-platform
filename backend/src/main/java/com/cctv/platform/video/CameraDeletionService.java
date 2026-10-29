package com.cctv.platform.video;

import com.cctv.platform.auth.CurrentUser;
import com.cctv.platform.camera.CameraService;
import com.cctv.platform.common.ApiException;
import org.springframework.stereotype.Service;

/**
 * Camera deletion (UC-02). A camera with footage is only deleted when the admin confirms
 * deleting the footage too; each video then goes through {@link VideoService#delete}, so its
 * files are removed and every deletion is audited. Lives in this package because it needs
 * the video service, which itself depends on {@link CameraService}.
 */
@Service
public class CameraDeletionService {

    private final CameraService cameras;
    private final VideoRepository videos;
    private final VideoService videoService;

    public CameraDeletionService(CameraService cameras, VideoRepository videos, VideoService videoService) {
        this.cameras = cameras;
        this.videos = videos;
        this.videoService = videoService;
    }

    public void delete(long cameraId, boolean deleteFootage, CurrentUser actor) {
        if (videos.existsByCameraId(cameraId)) {
            if (!deleteFootage) {
                throw ApiException.conflict("This camera has footage. Delete with deleteFootage=true to remove it too.");
            }
            for (Video video : videos.findByCameraIdOrderById(cameraId)) {
                videoService.delete(video.getId(), actor);
            }
        }
        cameras.delete(cameraId, actor);
    }
}
