package com.shemhazai.translationmcpserver.tools;

import com.shemhazai.translationmcpserver.client.TranslationApiClient;
import com.shemhazai.translationmcpserver.storage.StorageService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class TranslationTools {

    private final TranslationApiClient client;
    private final StorageService storage;

    public TranslationTools(TranslationApiClient client, StorageService storage) {
        this.client = client;
        this.storage = storage;
    }

    @McpTool(
            description = """
                    Get the current status of a single image translation job.
                    Returns the job status and translation progress.
                    """
    )
    public String getTranslationStatus(
            @McpToolParam(
                    description = "The single image translation job ID",
                    required = true
            )
            String jobId
    ) {
        return client.getTranslationStatus(jobId);
    }

    @McpTool(
            description = """
                    Get the current status of a batch manga translation job.
                    Returns overall progress and the current image being translated.
                    """
    )
    public String getBatchTranslationStatus(
            @McpToolParam(
                    description = "The batch translation job ID",
                    required = true
            )
            String jobId
    ) {
        return client.getBatchStatus(jobId);
    }

    @McpTool(
            description = """
                    Submit a single manga image for translation.
                    The image must be accessible as a file on the MCP server.
                    Returns the created translation job.
                    """
    )
    public String translateImage(
            @McpToolParam(
                    description = "Absolute path to the image file on the MCP server",
                    required = true
            )
            String filePath,

            @McpToolParam(
                    description = "Source language, for example ja",
                    required = true
            )
            String sourceLang,

            @McpToolParam(
                    description = "Target language, for example en",
                    required = true
            )
            String targetLang,

            @McpToolParam(
                    description = "Translator backend, for example offline",
                    required = true
            )
            String translator
    ) {
        return client.submitTranslation(
                Path.of(filePath),
                sourceLang,
                targetLang,
                translator
        );
    }

    @McpTool(
            description = """
                        Submit an uploaded manga ZIP archive for batch translation.
        
                        filePath must be the absolute path of the uploaded ZIP file
                        provided by the Open WebUI file bridge. Do not invent paths
                        and do not treat the ZIP contents as text.
        
                        Returns the created batch translation job.
                    """
    )
    public String translateBatch(
            @McpToolParam(
                    description = "Absolute path to the ZIP archive on the MCP server",
                    required = true
            )
            String filePath,

            @McpToolParam(
                    description = "Source language, for example ja",
                    required = true
            )
            String sourceLang,

            @McpToolParam(
                    description = "Target language, for example en",
                    required = true
            )
            String targetLang,

            @McpToolParam(
                    description = "Translator backend, for example offline",
                    required = true
            )
            String translator
    ) {
        return client.submitBatch(
                Path.of(filePath),
                sourceLang,
                targetLang,
                translator
        );
    }

    @McpTool(
            description = """
                Download the translated image after a single-image
                translation job has completed. Saves the result to
                the specified absolute output path and publishes it
                to object storage. The result includes a public
                download URL with no auth and no expiry: quote it
                verbatim in the reply as a markdown image link so it
                renders as an attachment in the chat.
                """
    )
    public String getTranslationResult(
            @McpToolParam(
                    description = "Completed single-image translation job ID",
                    required = true
            )
            String jobId,

            @McpToolParam(
                    description = "Absolute destination path for the translated image",
                    required = true
            )
            String outputPath
    ) throws java.io.IOException {
        Path savedFile = client.downloadTranslationResult(
                jobId,
                Path.of(outputPath)
        );

        return resultMessage("Translation result saved to: " + savedFile, jobId, savedFile);
    }

    @McpTool(
            description = """
                Download the ZIP archive after a batch translation
                job has completed. Saves the archive to the specified
                absolute output path and publishes it to object
                storage. The result includes a public download URL
                with no auth and no expiry: quote it verbatim in the
                reply as a markdown link so it renders as a
                downloadable attachment in the chat.
                """
    )
    public String getBatchTranslationResult(
            @McpToolParam(
                    description = "Completed batch translation job ID",
                    required = true
            )
            String jobId,

            @McpToolParam(
                    description = "Absolute destination path for the translated ZIP archive",
                    required = true
            )
            String outputPath
    ) throws java.io.IOException {
        Path savedFile = client.downloadBatchResult(
                jobId,
                Path.of(outputPath)
        );

        return resultMessage("Batch translation result saved to: " + savedFile, jobId, savedFile);
    }

    private String resultMessage(String savedLine, String jobId, Path savedFile)
            throws java.io.IOException {
        String url = storage.publish(savedFile, storage.keyFor(jobId, savedFile));
        if (url == null) {
            return savedLine;
        }
        return savedLine
                + "\nPublic download URL (no auth, no expiry): " + url
                + "\nIn the reply to the user include this exact URL as a markdown link"
                + " (image files as a markdown image, archives as a plain link).";
    }
}