package com.shemhazai.translationmcpserver.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Publishes translated result files to the MinIO bucket so tool results
 * carry a permanent public URL (bucket is public-read, GetObject-only;
 * writes here are the only authenticated operation).
 *
 * Uploads sign against the internal endpoint; the returned URL is the
 * plain public path-style URL, so it is host-reachable from any device
 * on the network and never expires.
 */
@Component
public class StorageService {

    private final S3Client s3;
    private final String bucket;
    private final String publicBaseUrl;
    private final boolean configured;

    public StorageService(
            @Value("${storage.endpoint:}") String endpoint,
            @Value("${storage.access-key:}") String accessKey,
            @Value("${storage.secret-key:}") String secretKey,
            @Value("${storage.bucket:}") String bucket,
            @Value("${storage.public-base-url:}") String publicBaseUrl
    ) {
        this.configured = !endpoint.isBlank() && !accessKey.isBlank()
                && !secretKey.isBlank() && !bucket.isBlank();
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        this.s3 = configured
                ? S3Client.builder()
                        .endpointOverride(URI.create(endpoint))
                        .region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)))
                        .forcePathStyle(true)
                        .build()
                : null;
    }

    /**
     * Uploads the file under key and returns the public URL,
     * or null when storage is not configured (callers keep the
     * saved-path-only fallback).
     */
    public String publish(Path file, String key) throws IOException {
        if (!configured) {
            return null;
        }

        ensureBucket();

        s3.putObject(b -> b
                .bucket(bucket)
                .key(key)
                .contentType(contentType(file)), file);

        return publicBaseUrl + "/" + bucket + "/" + key;
    }

    private void ensureBucket() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(bucket));
        }
    }

    /**
     * Explicit map first — browsers treat octet-stream as opaque download
     * bytes instead of image previews, and .zip probe is unreliable across
     * systems.
     */
    private String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".zip")) {
            return "application/zip";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        try {
            String probed = Files.probeContentType(file);
            if (probed != null) {
                return probed;
            }
        } catch (IOException ignored) {
        }
        return "application/octet-stream";
    }

    /** Key segment: prefix (job id) + sanitized filename, URL-safe. */
    public String keyFor(String prefix, Path file) {
        String name = file.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        return prefix + "/" + name;
    }
}
