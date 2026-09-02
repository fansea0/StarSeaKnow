package com.starsea.ai.chunking.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starsea.ai.chunking.api.ChunkingApiModels.ChunkResponse;
import com.starsea.ai.chunking.api.ChunkingApiModels.ConfirmRequest;
import com.starsea.ai.chunking.api.ChunkingApiModels.EditChunkRequest;
import com.starsea.ai.domain.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerChunkOverlapContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void document_chunk_models_persisted_per_chunk_overlap_settings() {
        Set<String> fields = Arrays.stream(DocumentChunk.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet());

        assertTrue(fields.contains("overlapEnabled"));
        assertTrue(fields.contains("overlapTokenLimit"));
    }

    @Test
    void migration_adds_bounded_settings_and_backfills_legacy_context() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/V8__add_per_chunk_overlap_settings.sql")) {
            assertNotNull(stream, "per-chunk overlap migration must be packaged");
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").toLowerCase();
            assertTrue(sql.contains("overlap_enabled boolean not null default false"));
            assertTrue(sql.contains("overlap_token_limit integer not null default 40"));
            assertTrue(sql.contains("overlap_token_limit between 1 and 512"));
            assertTrue(sql.contains("context_policy"));
            assertTrue(sql.contains("overlap_content"));
        }
    }

    @Test
    void edit_request_binds_body_settings_and_required_lock_version() throws Exception {
        assertEquals(List.of("content", "overlapEnabled", "overlapTokenLimit", "lockVersion"),
                recordComponents(EditChunkRequest.class));
        EditChunkRequest request = objectMapper.readValue("""
                {"content":"正文","overlapEnabled":true,"overlapTokenLimit":64,"lockVersion":3}
                """, EditChunkRequest.class);
        JsonNode json = objectMapper.valueToTree(request);

        assertEquals("正文", json.get("content").asText());
        assertTrue(json.get("overlapEnabled").asBoolean());
        assertEquals(64, json.get("overlapTokenLimit").asInt());
        assertEquals(3, json.get("lockVersion").asInt());
    }

    @Test
    void confirm_request_retains_only_lock_version() {
        assertEquals(List.of("lockVersion"), recordComponents(ConfirmRequest.class));
    }

    @Test
    void chunk_response_exposes_settings_and_derived_overlap_without_internal_source_id()
            throws Exception {
        assertTrue(recordComponents(ChunkResponse.class).containsAll(List.of(
                "overlapEnabled", "overlapTokenLimit", "overlapContent",
                "overlapTokenCount", "overlapUnavailableReason")));
        ChunkResponse response = objectMapper.readValue("""
                {"publicId":"10000000-0000-0000-0000-000000000021","position":1,
                 "content":"正文","sectionPath":[],"sourceLocator":{},"tokenCount":2,
                 "status":0,"isModified":true,"lockVersion":4,
                 "overlapEnabled":true,"overlapTokenLimit":40,
                 "overlapContent":"前文。","overlapTokenCount":3,
                 "overlapUnavailableReason":null}
                """, ChunkResponse.class);
        JsonNode json = objectMapper.valueToTree(response);

        assertTrue(json.get("overlapEnabled").asBoolean());
        assertEquals(40, json.get("overlapTokenLimit").asInt());
        assertEquals("前文。", json.get("overlapContent").asText());
        assertEquals(3, json.get("overlapTokenCount").asInt());
        assertTrue(json.get("overlapUnavailableReason").isNull());
        assertTrue(json.get("overlapSourceChunkId") == null);
    }

    private static List<String> recordComponents(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
    }
}
