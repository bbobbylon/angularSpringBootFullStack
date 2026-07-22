package com.bob.angularspringbootfullstack.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * Abstraction over the physical storage backend used for user profile images.
 *
 * <p>Two implementations are provided:
 * <ul>
 *   <li>{@link com.bob.angularspringbootfullstack.service.serviceimpl.LocalImageStorageService} —
 *       writes images to the configured local filesystem path; active when
 *       {@code IMAGE_STORAGE_TYPE} is {@code local} (the default).  Suitable for local
 *       development, Docker single-host, and Docker Compose deployments where a named
 *       volume is mounted at {@code IMAGE_STORAGE_PATH}.</li>
 *   <li>{@link com.bob.angularspringbootfullstack.service.serviceimpl.S3ImageStorageService} —
 *       uploads images to an AWS S3 bucket and returns the public S3 URL; active when
 *       {@code IMAGE_STORAGE_TYPE=s3}. Required for multi-instance / ephemeral-host deployments
 *       such as ECS Fargate where local disk does not survive task restarts.</li>
 * </ul>
 *
 * <p>The active implementation is selected via {@code @ConditionalOnProperty} on
 * {@code app.image.storage-type} (environment variable {@code IMAGE_STORAGE_TYPE}).
 * Callers depend only on this interface so no code change is needed when switching
 * between local and S3 storage.
 */
public interface ImageStorageService {

    /**
     * Stores the uploaded profile image for the given user and returns the public URL at
     * which the image can be retrieved.
     *
     * <p>For local storage the URL points to the application's own
     * {@code GET /user/image/{email}.png} endpoint. For S3 storage the URL is the
     * object's S3 HTTPS URL.
     *
     * @param email the user's email address, used as the image filename
     * @param image the uploaded image file from the multipart request
     * @return the public URL that can be stored in the user's {@code image_url} column
     *         and consumed by the Angular frontend
     */
    String store(String email, MultipartFile image);
}
