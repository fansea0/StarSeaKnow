package com.starsea.ai.chunking.config;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.starsea.ai.chunking.spi.TokenCounter;
import com.starsea.ai.chunking.token.HuggingFaceTokenCounter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Installs the verified BGE tokenizer used by every token-budget calculation. */
@Configuration
public class ChunkingConfiguration {

    static final String TOKENIZER_ID = "BAAI/bge-base-zh-v1.5@7dfbf196";

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
