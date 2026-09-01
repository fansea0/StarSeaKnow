package com.starsea.ai.chunking.config;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.chunking.token.HuggingFaceTokenCounter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/** Installs the verified BGE tokenizer used by every token-budget calculation. */
@Configuration
public class ChunkingConfiguration {

    static final String TOKENIZER_ID = "BAAI/bge-base-zh-v1.5@7dfbf196";

    @Bean(name = "chunkingTaskExecutor")
    public ThreadPoolTaskExecutor chunkingTaskExecutor(
            @Value("${chunking.executor.core-size}") int coreSize,
            @Value("${chunking.executor.max-size}") int maxSize,
            @Value("${chunking.executor.queue-capacity}") int queueCapacity,
            @Value("${chunking.executor.thread-name-prefix}") String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    @Bean(name = "chunkingRecoveryScheduler")
    public ThreadPoolTaskScheduler chunkingRecoveryScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("chunking-recovery-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }

    @Bean(destroyMethod = "close")
    public TokenCounter bgeTokenCounter(
            @Value("${chunking.tokenizer.resource}") String tokenizerResource,
            @Value("${chunking.tokenizer.sha256}") String expectedSha256) {
        ClassPathResource resource = new ClassPathResource(tokenizerResource);
        verifyChecksum(resource, expectedSha256);
        try (InputStream input = resource.getInputStream()) {
            return new HuggingFaceTokenCounter(HuggingFaceTokenizer.newInstance(input, Map.of()), TOKENIZER_ID);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load tokenizer resource: " + tokenizerResource, e);
        }
    }

    private void verifyChecksum(ClassPathResource resource, String expectedSha256) {
        try (InputStream input = resource.getInputStream()) {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.readAllBytes());
            String actualSha256 = HexFormat.of().formatHex(digest);
            if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                throw new IllegalStateException("Tokenizer resource checksum does not match the configured SHA-256");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Tokenizer resource is required but unavailable: " + resource.getPath(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
