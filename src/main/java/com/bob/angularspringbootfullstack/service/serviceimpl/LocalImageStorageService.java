package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.service.ImageStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * {@link ImageStorageService} implementation that persists profile images on the local
 * filesystem and serves them via the application's own {@code GET /user/image/{email}.png}
 * endpoint.
 *
 * <p>Active when {@code app.image.storage-type} is {@code local} or absent (the default).
 * The storage directory is resolved from {@code app.image.storage-path}
 * (environment variable {@code IMAGE_STORAGE_PATH}); for Docker deployments this path
 * should point to a mounted named volume so images survive container restarts.
 *
 * <p>Callers receive the public URL for the stored image, built from the current
 * request context via {@link ServletUriComponentsBuilder} so the host and port are
 * always correct regardless of the deployment address.
 *
 * <p>This class is the direct successor to the inline {@code saveImage} / {@code setUserImageUrl}
 * methods that previously lived in {@code UserRepoImpl}. Extracting them here satisfies the
 * {@link ImageStorageService} contract and keeps the repository layer free of filesystem I/O.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.image.storage-type", havingValue = "local", matchIfMissing = true)
public class LocalImageStorageService implements ImageStorageService {

    /**
     * Root directory where profile images are written; resolved from
     * {@code app.image.storage-path} (env {@code IMAGE_STORAGE_PATH}).
     * Defaults to {@code ~/tesseraapp/images} in the dev profile via {@code application.yml}.
     */
    @Value("${app.image.storage-path}")
    private String imageStoragePath;

    /**
     * Saves the uploaded file to {@code {imageStoragePath}/{email}.png} (creating the
     * directory if necessary, overwriting any previous image) and returns the URL of
     * the {@code GET /user/image/{email}.png} endpoint that serves it.
     *
     * @param email the user's email address, used as the image filename
     * @param image the uploaded image file
     * @return the absolute URL of the application's image-serving endpoint for this user
     * @throws ApiException if the storage directory cannot be created or the file write fails
     */
    @Override
    public String store(String email, MultipartFile image) {
        Path storageDir = Paths.get(imageStoragePath).toAbsolutePath().normalize();

        if (!Files.exists(storageDir)) {
            try {
                Files.createDirectories(storageDir);
                log.info("Created profile image storage directory: {}", storageDir);
            } catch (IOException e) {
                log.error("Failed to create profile image directory: {}", storageDir, e);
                throw new ApiException("Could not create the image storage directory. Please try again.");
            }
        }

        try {
            Files.copy(image.getInputStream(), storageDir.resolve(email + ".png"), REPLACE_EXISTING);
            log.info("Profile image saved for user: {}", email);
        } catch (IOException e) {
            log.error("Failed to write profile image for user '{}': {}", email, e.getMessage(), e);
            throw new ApiException("An error occurred while saving the profile image. Please try again.");
        }

        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/user/image/" + email + ".png")
                .toUriString();
    }
}
