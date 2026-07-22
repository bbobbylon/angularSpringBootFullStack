package com.bob.angularspringbootfullstack.service.serviceimpl;

import com.bob.angularspringbootfullstack.exception.ApiException;
import com.bob.angularspringbootfullstack.service.ImageStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;

/**
 * {@link ImageStorageService} implementation that uploads profile images to AWS S3.
 *
 * <p>Active only when {@code app.image.storage-type=s3} (environment variable
 * {@code IMAGE_STORAGE_TYPE=s3}). This implementation is required for any deployment on
 * ephemeral-disk infrastructure (ECS Fargate, App Runner, Kubernetes) where a local
 * filesystem volume does not survive task/pod restarts. Images are uploaded to the
 * bucket named by {@code aws.s3.bucket} ({@code AWS_S3_BUCKET} env var) and served
 * directly from S3 via the public HTTPS URL stored in the user's {@code image_url} column.
 *
 * <p>The {@link S3Client} bean is provided by
 * {@link com.bob.angularspringbootfullstack.configuration.AwsS3Config} and is also
 * conditional on {@code app.image.storage-type=s3}, so neither this service nor the S3
 * client is instantiated in local-storage mode.
 *
 * <p><b>IAM requirements:</b> the task/instance role (or the {@code AWS_ACCESS_KEY_ID} /
 * {@code AWS_SECRET_ACCESS_KEY} credentials) must have {@code s3:PutObject} on the target
 * bucket. For ECS Fargate the recommended pattern is an ECS task role with an inline policy
 * granting {@code s3:PutObject} and {@code s3:GetObject} on
 * {@code arn:aws:s3:::${AWS_S3_BUCKET}/*}.
 *
 * <p><b>CORS:</b> for the Angular frontend to load profile images directly from S3, the
 * bucket must have a CORS configuration that allows {@code GET} from the application's
 * origin. See {@code aws/README.md} for the required CORS JSON.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.image.storage-type", havingValue = "s3")
public class S3ImageStorageService implements ImageStorageService {

    private final S3Client s3Client;

    /**
     * Target S3 bucket name; resolved from {@code aws.s3.bucket}
     * (environment variable {@code AWS_S3_BUCKET}).
     */
    @Value("${aws.s3.bucket}")
    private String bucketName;

    /**
     * AWS region for constructing the public S3 object URL; resolved from
     * {@code aws.region} ({@code AWS_REGION}), defaulting to {@code us-east-1}.
     */
    @Value("${aws.region:us-east-1}")
    private String region;

    /**
     * Uploads the image to S3 as {@code {email}.png} and returns the public HTTPS URL.
     *
     * <p>The object key is simply {@code {email}.png}. The public URL has the form
     * {@code https://{bucket}.s3.{region}.amazonaws.com/{email}.png}. This URL is stored
     * in the user's {@code image_url} column and consumed directly by the Angular frontend
     * as the {@code <img>} src — the application does NOT proxy S3 traffic.
     *
     * @param email the user's email address, used as the S3 object key
     * @param image the uploaded image file
     * @return the public S3 HTTPS URL for the uploaded image
     * @throws ApiException wrapping any S3 SDK or I/O error
     */
    @Override
    public String store(String email, MultipartFile image) {
        String key = email + ".png";
        try {
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType("image/png")
                    .contentLength(image.getSize())
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromInputStream(image.getInputStream(), image.getSize()));
            log.info("Profile image uploaded to S3 for user: {} (bucket={}, key={})", email, bucketName, key);
        } catch (IOException | software.amazon.awssdk.core.exception.SdkException e) {
            log.error("Failed to upload profile image to S3 for user '{}': {}", email, e.getMessage(), e);
            throw new ApiException("An error occurred while uploading the profile image. Please try again.");
        }

        return "https://" + bucketName + ".s3." + region + ".amazonaws.com/" + key;
    }
}
