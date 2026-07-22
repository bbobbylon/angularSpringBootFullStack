package com.bob.angularspringbootfullstack.configuration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS S3 client configuration — active only when {@code app.image.storage-type=s3}
 * (environment variable {@code IMAGE_STORAGE_TYPE=s3}).
 *
 * <p>Provides the {@link S3Client} bean consumed by
 * {@link com.bob.angularspringbootfullstack.service.serviceimpl.S3ImageStorageService}.
 * The client is built with {@link DefaultCredentialsProvider}, which resolves credentials in
 * the standard AWS SDK chain:
 * <ol>
 *   <li>Java system properties ({@code aws.accessKeyId} / {@code aws.secretAccessKey})</li>
 *   <li>Environment variables ({@code AWS_ACCESS_KEY_ID} / {@code AWS_SECRET_ACCESS_KEY})</li>
 *   <li>AWS credential file ({@code ~/.aws/credentials})</li>
 *   <li>ECS task role / EC2 instance profile (preferred in cloud deployments — no key to rotate)</li>
 * </ol>
 *
 * <p>For ECS Fargate the recommended approach is to attach a task role with
 * {@code s3:PutObject} and {@code s3:GetObject} on the target bucket. This eliminates
 * long-lived credentials entirely. For local development, set {@code AWS_ACCESS_KEY_ID}
 * and {@code AWS_SECRET_ACCESS_KEY} in your {@code .env} file.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.image.storage-type", havingValue = "s3")
public class AwsS3Config {

    /**
     * AWS region used to build the S3 client; resolved from {@code aws.region}
     * (environment variable {@code AWS_REGION}), defaulting to {@code us-east-1}.
     */
    @Value("${aws.region:us-east-1}")
    private String region;

    /**
     * Builds an AWS SDK v2 {@link S3Client} scoped to the configured region.
     *
     * <p>Credentials are discovered automatically via {@link DefaultCredentialsProvider}
     * (see class-level Javadoc for resolution order). The client is a singleton bean; the
     * SDK manages an underlying HTTP connection pool internally.
     *
     * @return a configured, ready-to-use {@link S3Client} singleton
     */
    @Bean
    public S3Client s3Client() {
        log.info("Initialising AWS S3 client for region: {}", region);
        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
