package com.example.datastudio.service;

import ai.accelerator.CountryCode;
import ai.accelerator.DataStudioSDK;
import ai.accelerator.DataStudioSDK.*;
import ai.accelerator.exceptions.*;
import ai.accelerator.wait.WaitStrategy;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Service class for processing documents using the DataStudio SDK.
 *
 * <p>Since SDK 0.2.0-alpha this service no longer hand-rolls a polling loop:
 * it relies on the SDK's built-in {@code waitForCompletion(...)} which polls
 * {@code getStatus} until the document reaches a terminal state
 * ({@code completed}, {@code ready_for_review}, {@code failed},
 * {@code review_expired}) or the {@link WaitStrategy} budget is exceeded.
 */
public class DocumentProcessorService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentProcessorService.class);

    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration DEFAULT_MAX_WAIT = Duration.ofMinutes(5);

    /** Terminal statuses returned by the API in the {@code status} field of the wait payload. */
    private static final Set<String> SUCCESSFUL_TERMINAL_STATES =
            Set.of("completed", "ready_for_review");
    private static final Set<String> FAILED_TERMINAL_STATES =
            Set.of("failed", "review_expired");

    private final DataStudioSDK sdk;
    private final ExecutorService executorService;
    private final WaitStrategy waitStrategy;

    /**
     * Create a new service with default wait settings (2 s poll interval, 5 min budget).
     */
    public DocumentProcessorService(DataStudioSDK sdk) {
        this(sdk, new WaitStrategy(DEFAULT_POLL_INTERVAL, DEFAULT_MAX_WAIT));
    }

    /**
     * Create a new service with a custom {@link WaitStrategy}.
     *
     * @param sdk          The initialized SDK instance.
     * @param waitStrategy Polling strategy passed to {@code sdk.waitForCompletion(...)}.
     */
    public DocumentProcessorService(DataStudioSDK sdk, WaitStrategy waitStrategy) {
        this.sdk = sdk;
        this.waitStrategy = waitStrategy;
        this.executorService = Executors.newCachedThreadPool();
    }

    /**
     * Upload a document and block until the SDK reports a terminal state.
     *
     * <p>Returns the full result payload from {@code sdk.getResult(processId)}.
     *
     * @throws DataStudioException  on upload failure, terminal {@code failed}/{@code review_expired},
     *                              or wait timeout (rethrown as-is from the SDK).
     * @throws InterruptedException if the polling thread is interrupted.
     */
    public JSONObject uploadAndWaitForResult(String userName,
                                             DocType docType,
                                             Path filePath,
                                             Map<String, String> metadata)
            throws DataStudioException, InterruptedException {

        logger.info("Uploading document: {} as {}", filePath.getFileName(), docType);

        UploadResult uploadResult = sdk.uploadDocument(userName, docType, filePath, metadata, CountryCode.ES);
        String processId = uploadResult.processId();

        logger.info("Document uploaded with processId: {}, initial status: {}",
                processId, uploadResult.status());

        if (uploadResult.status() == UploadResultStatus.FAILED) {
            throw new DataStudioException("Document upload failed immediately");
        }

        return waitForResult(processId);
    }

    /**
     * Upload a document and process it asynchronously, delivering the result via callbacks.
     */
    public CompletableFuture<JSONObject> uploadAndProcessAsync(
            String userName,
            DocType docType,
            Path filePath,
            Map<String, String> metadata,
            Consumer<JSONObject> onSuccess,
            Consumer<Throwable> onError) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                JSONObject result = uploadAndWaitForResult(userName, docType, filePath, metadata);
                if (onSuccess != null) {
                    onSuccess.accept(result);
                }
                return result;
            } catch (Exception e) {
                if (onError != null) {
                    onError.accept(e);
                }
                throw new RuntimeException(e);
            }
        }, executorService);
    }

    /**
     * Upload a document without waiting (use when you have webhooks configured).
     */
    public UploadResult uploadDocument(String userName,
                                       DocType docType,
                                       Path filePath,
                                       Map<String, String> metadata)
            throws DataStudioException {
        logger.info("Uploading document: {}", filePath.getFileName());
        return sdk.uploadDocument(userName, docType, filePath, metadata);
    }

    /**
     * Upload a document without waiting, specifying a country code.
     */
    public UploadResult uploadDocument(String userName,
                                       DocType docType,
                                       Path filePath,
                                       Map<String, String> metadata,
                                       CountryCode countryCode)
            throws DataStudioException {
        logger.info("Uploading document: {} with country: {}", filePath.getFileName(), countryCode);
        return sdk.uploadDocument(userName, docType, filePath, metadata, countryCode);
    }

    /**
     * Check the current status of a document.
     */
    public UploadResult checkStatus(String processId) throws DataStudioException {
        logger.debug("Checking status for processId: {}", processId);
        return sdk.getStatus(processId);
    }

    /**
     * Get the result of a processed document.
     */
    public JSONObject getResult(String processId) throws DataStudioException {
        logger.debug("Getting result for processId: {}", processId);
        return sdk.getResult(processId);
    }

    /**
     * Wait for a previously uploaded document to reach a terminal state and return its full result.
     *
     * <p>Throws {@link DataStudioException} for failed/review_expired terminals (the SDK throws
     * with {@code errorCode = "wait_timeout"} when {@code maxWait} is exceeded).
     */
    public JSONObject waitForResult(String processId)
            throws DataStudioException, InterruptedException {

        logger.info("Waiting for completion of processId: {} (pollInterval={}, maxWait={})",
                processId, waitStrategy.pollInterval(), waitStrategy.maxWait());

        JSONObject statusPayload = sdk.waitForCompletion(processId, waitStrategy);
        String terminalState = statusPayload.optString("status");
        logger.info("Document {} reached terminal state: {}", processId, terminalState);

        if (FAILED_TERMINAL_STATES.contains(terminalState)) {
            String error = statusPayload.optString("error", "Document processing did not succeed");
            throw new DataStudioException("Terminal state '" + terminalState + "': " + error);
        }
        if (!SUCCESSFUL_TERMINAL_STATES.contains(terminalState)) {
            // Forward-compat: unknown terminals are treated as transient failures
            // rather than silently returning a partial payload.
            throw new DataStudioException("Unrecognized terminal state '" + terminalState + "'");
        }

        return sdk.getResult(processId);
    }

    public void shutdown() {
        executorService.shutdown();
    }
}
