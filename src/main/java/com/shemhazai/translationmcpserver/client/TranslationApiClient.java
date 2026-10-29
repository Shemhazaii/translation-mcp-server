package com.shemhazai.translationmcpserver.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class TranslationApiClient {

    private final RestClient client;

    public TranslationApiClient(
            RestClient.Builder builder,
            @Value("${translation.api.base-url}") String baseUrl
    ) {
        this.client = builder
                .baseUrl(baseUrl)
                .build();
    }

    public String getTranslationStatus(String jobId) {
        return client.get()
                .uri("/api/v1/translations/{jobId}", jobId)
                .retrieve()
                .body(String.class);
    }

    public String getBatchStatus(String jobId) {
        return client.get()
                .uri("/api/v1/translations/batch/{jobId}", jobId)
                .retrieve()
                .body(String.class);
    }

    public String submitTranslation(
            Path file,
            String sourceLang,
            String targetLang,
            String translator
    ) {
        MultiValueMap<String, Object> body =
                new LinkedMultiValueMap<>();

        body.add(
                "file",
                new FileSystemResource(file)
        );
        body.add("sourceLang", sourceLang);
        body.add("targetLang", targetLang);
        body.add("translator", translator);

        return client.post()
                .uri("/api/v1/translations")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    public String submitBatch(
            Path file,
            String sourceLang,
            String targetLang,
            String translator
    ) {
        MultiValueMap<String, Object> body =
                new LinkedMultiValueMap<>();

        body.add(
                "file",
                new FileSystemResource(file)
        );
        body.add("sourceLang", sourceLang);
        body.add("targetLang", targetLang);
        body.add("translator", translator);

        return client.post()
                .uri("/api/v1/translations/batch")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    public Path downloadTranslationResult(
            String jobId,
            Path outputFile
    ) throws IOException {
        return downloadResult(
                "/api/v1/translations/{jobId}/result",
                jobId,
                outputFile
        );
    }

    public Path downloadBatchResult(
            String jobId,
            Path outputFile
    ) throws IOException {
        return downloadResult(
                "/api/v1/translations/batch/{jobId}/result",
                jobId,
                outputFile
        );
    }

    private Path downloadResult(
            String uri,
            String jobId,
            Path outputFile
    ) throws IOException {
        Path destination = outputFile.toAbsolutePath().normalize();

        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }

        byte[] result = client.get()
                .uri(uri, jobId)
                .retrieve()
                .body(byte[].class);

        if (result == null || result.length == 0) {
            throw new IOException(
                    "Translation result is empty for job: " + jobId
            );
        }

        Path temporaryFile = Files.createTempFile(
                destination.getParent(),
                "translation-",
                ".tmp"
        );

        try {
            Files.write(temporaryFile, result);

            Files.move(
                    temporaryFile,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporaryFile);
        }

        return destination;
    }
}