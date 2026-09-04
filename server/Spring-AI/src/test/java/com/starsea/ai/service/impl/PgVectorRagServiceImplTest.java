package com.starsea.ai.service.impl;

import com.starsea.ai.auth.AuthContext;
import com.starsea.ai.chunking.model.ChunkStatus;
import com.starsea.ai.chunking.model.ChunkType;
import com.starsea.ai.domain.DocumentChunk;
import com.starsea.ai.domain.File;
import com.starsea.ai.mapper.DocumentChunkMapper;
import com.starsea.ai.openapi.retrieval.RetrievalQuery;
import com.starsea.ai.openapi.retrieval.RetrievedChunk;
import com.starsea.ai.service.FileService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgVectorRagServiceImplTest {

    private static final long TENANT_ID = 1L;
    private static final long KNOWLEDGE_ID = 10L;
    private static final long FILE_ID = 20L;
    private static final UUID FILE_PUBLIC_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID CURRENT_FILE_PUBLIC_ID =
            UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID FIRST_CHUNK_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND_CHUNK_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID THIRD_CHUNK_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID FOURTH_CHUNK_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID FIRST_PARENT_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID SECOND_PARENT_ID =
            UUID.fromString("66666666-6666-6666-6666-666666666666");

    @BeforeEach
    void setAuth() {
        AuthContext.set(new AuthContext(
                AuthContext.Kind.BUSINESS, 7L, TENANT_ID, "tenant_admin", "jti"));
    }

    @AfterEach
    void clearAuth() {
        AuthContext.clear();
    }

    @Test
    void filters_stale_and_malformed_candidates_and_uses_database_index_content() {
        Fixture fixture = fixture();
        Document valid = candidate(FIRST_CHUNK_ID, "stale vector text", 0.94);
        Document draft = candidate(SECOND_CHUNK_ID, "draft vector text", 0.91);
        Document missing = candidate(THIRD_CHUNK_ID, "missing vector text", 0.89);
        Document malformed = Document.builder()
                .id("not-a-uuid")
                .text("malformed vector text")
                .metadata(candidateMetadata("also-not-a-uuid"))
                .score(0.99)
                .build();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(malformed, valid, draft, missing));
        when(fixture.chunkMapper.findActiveByPublicIds(eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), any()))
                .thenReturn(List.of(
                        chunk(FIRST_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                                ChunkStatus.ACTIVE, 0, "database index content"),
                        chunk(SECOND_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                                ChunkStatus.DRAFT, 1, "draft database content")));

        List<RetrievedChunk> result = fixture.service.retrieve(
                new RetrievalQuery("stars", Set.of(KNOWLEDGE_ID), 2, 0.0));

        assertEquals(1, result.size());
        assertEquals(FIRST_CHUNK_ID, result.get(0).chunkId());
        assertEquals("database index content", result.get(0).content());
        assertEquals(List.of("Guide", "Details"), result.get(0).sectionPath());
        assertEquals(Map.of("startLine", 7, "endLine", 11, "blockIds", List.of("b-1")),
                result.get(0).sourceLocator());
        ArgumentCaptor<SearchRequest> search = ArgumentCaptor.forClass(SearchRequest.class);
        verify(fixture.vectorStore).similaritySearch(search.capture());
        assertEquals(6, search.getValue().getTopK());
        String filter = search.getValue().getFilterExpression().toString();
        assertTrue(filter.contains("tenantId"));
        assertTrue(filter.contains("knowledgeId"));
        assertTrue(filter.contains("fileId"));
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<UUID>> ids = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(fixture.chunkMapper).findActiveByPublicIds(
                eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), ids.capture());
        assertEquals(List.of(FIRST_CHUNK_ID, SECOND_CHUNK_ID, THIRD_CHUNK_ID),
                ids.getValue());
    }

    @Test
    void preserves_similarity_order_deduplicates_stable_ids_and_truncates_after_filtering() {
        Fixture fixture = fixture();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                candidate(THIRD_CHUNK_ID, "third candidate", 0.97),
                candidate(FIRST_CHUNK_ID, "first candidate", 0.93),
                candidate(SECOND_CHUNK_ID, "second candidate", 0.90),
                candidate(FIRST_CHUNK_ID, "duplicate first", 0.70)));
        when(fixture.chunkMapper.findActiveByPublicIds(eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), any()))
                .thenReturn(List.of(
                        chunk(SECOND_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                                ChunkStatus.ACTIVE, 2, "database second"),
                        chunk(FIRST_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                                ChunkStatus.ACTIVE, 1, "database first"),
                        chunk(THIRD_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                                ChunkStatus.ACTIVE, 3, "database third")));

        List<RetrievedChunk> result = fixture.service.retrieve(
                new RetrievalQuery("stars", Set.of(KNOWLEDGE_ID), 2, 0.0));

        assertEquals(List.of(THIRD_CHUNK_ID, FIRST_CHUNK_ID),
                result.stream().map(RetrievedChunk::chunkId).toList());
        assertEquals(List.of(0.97, 0.93), result.stream().map(RetrievedChunk::score).toList());
        assertEquals(List.of("database third", "database first"),
                result.stream().map(RetrievedChunk::content).toList());
    }

    @Test
    void child_hit_returns_authoritative_parent_context_with_child_citation_and_score() {
        Fixture fixture = fixture();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(candidate(FIRST_CHUNK_ID, "stale child vector text", 0.94)));
        DocumentChunk child = childChunk(FIRST_CHUNK_ID, FIRST_PARENT_ID, 7,
                "child index content", "authoritative parent body");
        child.setParentSectionPath(List.of("Parent", "Details"));
        child.setParentSourceLocator(Map.of("pageNumber", 9, "startLine", 20, "endLine", 30));
        when(fixture.chunkMapper.findActiveByPublicIds(eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), any()))
                .thenReturn(List.of(child));

        RetrievedChunk result = fixture.service.retrieve(
                new RetrievalQuery("query", Set.of(KNOWLEDGE_ID), 3, 0.2)).get(0);

        assertEquals("标题：Parent > Details\n\nauthoritative parent body", result.content());
        assertEquals(FIRST_CHUNK_ID, result.chunkId());
        assertEquals(0.94, result.score());
        assertEquals(7, result.chunkIndex());
        assertEquals(9, result.pageNumber());
        assertEquals(List.of("Parent", "Details"), result.sectionPath());
        assertEquals(Map.of("pageNumber", 9, "startLine", 20, "endLine", 30), result.sourceLocator());
    }

    @Test
    void child_hits_deduplicate_by_parent_and_keep_first_hit_order_and_score() {
        Fixture fixture = fixture();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                candidate(FIRST_CHUNK_ID, "first child", 0.97),
                candidate(SECOND_CHUNK_ID, "same parent child", 0.93),
                candidate(THIRD_CHUNK_ID, "other parent child", 0.90)));
        when(fixture.chunkMapper.findActiveByPublicIds(eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), any()))
                .thenReturn(List.of(
                        childChunk(THIRD_CHUNK_ID, SECOND_PARENT_ID, 9,
                                "third child index", "second parent body"),
                        childChunk(SECOND_CHUNK_ID, FIRST_PARENT_ID, 7,
                                "second child index", "first parent body"),
                        childChunk(FIRST_CHUNK_ID, FIRST_PARENT_ID, 7,
                                "first child index", "first parent body")));

        List<RetrievedChunk> result = fixture.service.retrieve(
                new RetrievalQuery("query", Set.of(KNOWLEDGE_ID), 3, 0.2));

        assertEquals(List.of(FIRST_CHUNK_ID, THIRD_CHUNK_ID),
                result.stream().map(RetrievedChunk::chunkId).toList());
        assertEquals(List.of(0.97, 0.90), result.stream().map(RetrievedChunk::score).toList());
        assertEquals(List.of("标题：Parent\n\nfirst parent body", "标题：Parent\n\nsecond parent body"),
                result.stream().map(RetrievedChunk::content).toList());
        assertEquals(List.of(7, 9), result.stream().map(RetrievedChunk::chunkIndex).toList());
    }

    @Test
    void drops_child_rows_without_complete_active_scoped_parent_projection() {
        Fixture fixture = fixture();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                candidate(FIRST_CHUNK_ID, "missing parent", 0.99),
                candidate(SECOND_CHUNK_ID, "inactive parent", 0.98),
                candidate(THIRD_CHUNK_ID, "cross scope parent", 0.97),
                candidate(FOURTH_CHUNK_ID, "valid parent", 0.96)));
        DocumentChunk missingParent = childChunk(FIRST_CHUNK_ID, FIRST_PARENT_ID, 7,
                "first child index", "parent body");
        missingParent.setParentPublicId(null);
        DocumentChunk inactiveParent = childChunk(SECOND_CHUNK_ID, FIRST_PARENT_ID, 7,
                "second child index", "parent body");
        inactiveParent.setParentStatus(ChunkStatus.DRAFT.code());
        DocumentChunk crossScopeParent = childChunk(THIRD_CHUNK_ID, FIRST_PARENT_ID, 7,
                "third child index", "parent body");
        crossScopeParent.setParentContent(null);
        when(fixture.chunkMapper.findActiveByPublicIds(eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), any()))
                .thenReturn(List.of(missingParent, inactiveParent, crossScopeParent,
                        childChunk(FOURTH_CHUNK_ID, SECOND_PARENT_ID, 9,
                                "fourth child index", "valid parent body")));

        List<RetrievedChunk> result = fixture.service.retrieve(
                new RetrievalQuery("query", Set.of(KNOWLEDGE_ID), 4, 0.2));

        assertEquals(List.of(FOURTH_CHUNK_ID), result.stream().map(RetrievedChunk::chunkId).toList());
        assertEquals(List.of("标题：Parent\n\nvalid parent body"),
                result.stream().map(RetrievedChunk::content).toList());
    }

    @Test
    void drops_rows_outside_tenant_knowledge_and_enabled_file_scope() {
        Fixture fixture = fixture();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                candidate(FIRST_CHUNK_ID, "cross tenant", 0.97),
                candidate(SECOND_CHUNK_ID, "cross knowledge", 0.93),
                candidate(THIRD_CHUNK_ID, "disabled file", 0.90)));
        when(fixture.chunkMapper.findActiveByPublicIds(eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), any()))
                .thenReturn(List.of(
                        chunk(FIRST_CHUNK_ID, 99L, KNOWLEDGE_ID, FILE_ID,
                                ChunkStatus.ACTIVE, 0, "cross tenant"),
                        chunk(SECOND_CHUNK_ID, TENANT_ID, 999L, FILE_ID,
                                ChunkStatus.ACTIVE, 1, "cross knowledge"),
                        chunk(THIRD_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, 999L,
                                ChunkStatus.ACTIVE, 2, "disabled file")));

        List<RetrievedChunk> result = fixture.service.retrieve(
                new RetrievalQuery("stars", Set.of(KNOWLEDGE_ID), 3, 0.0));

        assertTrue(result.isEmpty());
    }

    @Test
    void accepts_valid_document_id_when_chunk_metadata_is_missing() {
        Fixture fixture = fixture();
        Map<String, Object> missingMetadataId = new LinkedHashMap<>(candidateMetadata(FOURTH_CHUNK_ID.toString()));
        missingMetadataId.remove("documentChunkId");
        Document missingMetadata = Document.builder()
                .id(FOURTH_CHUNK_ID.toString())
                .text("missing metadata id")
                .metadata(missingMetadataId)
                .score(0.80)
                .build();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(missingMetadata));
        when(fixture.chunkMapper.findActiveByPublicIds(eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), any()))
                .thenReturn(List.of(chunk(FOURTH_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                        ChunkStatus.ACTIVE, 4, "database fourth")));

        List<RetrievedChunk> result = fixture.service.retrieve(
                new RetrievalQuery("stars", Set.of(KNOWLEDGE_ID), 1, 0.0));

        assertEquals(List.of(FOURTH_CHUNK_ID), result.stream().map(RetrievedChunk::chunkId).toList());
        assertEquals(0.80, result.get(0).score());
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<UUID>> ids = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(fixture.chunkMapper).findActiveByPublicIds(
                eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), ids.capture());
        assertEquals(List.of(FOURTH_CHUNK_ID), ids.getValue());
    }

    @Test
    void rejects_malformed_document_id_even_when_metadata_id_is_valid() {
        assertCandidateRejected(Document.builder()
                .id("not-a-uuid")
                .text("malformed document id")
                .metadata(candidateMetadata(FIRST_CHUNK_ID.toString()))
                .score(0.99)
                .build());
    }

    @Test
    void rejects_valid_document_id_when_metadata_id_is_malformed() {
        assertCandidateRejected(Document.builder()
                .id(FIRST_CHUNK_ID.toString())
                .text("malformed metadata id")
                .metadata(candidateMetadata("not-a-uuid"))
                .score(0.99)
                .build());
    }

    @Test
    void rejects_valid_document_id_when_metadata_has_different_valid_id() {
        assertCandidateRejected(Document.builder()
                .id(FIRST_CHUNK_ID.toString())
                .text("mismatched metadata id")
                .metadata(candidateMetadata(SECOND_CHUNK_ID.toString()))
                .score(0.99)
                .build());
    }

    @Test
    void accepts_matching_document_and_metadata_ids() {
        Fixture fixture = fixture();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(candidate(FIRST_CHUNK_ID, "matching candidate", 0.91)));
        when(fixture.chunkMapper.findActiveByPublicIds(eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), any()))
                .thenReturn(List.of(chunk(FIRST_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                        ChunkStatus.ACTIVE, 0, "database first")));

        List<RetrievedChunk> result = fixture.service.retrieve(
                new RetrievalQuery("stars", Set.of(KNOWLEDGE_ID), 1, 0.0));

        assertEquals(List.of(FIRST_CHUNK_ID), result.stream().map(RetrievedChunk::chunkId).toList());
        assertEquals(0.91, result.get(0).score());
    }

    @Test
    void duplicate_chunk_id_retains_first_candidate_order_and_score() {
        Fixture fixture = fixture();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                candidate(FIRST_CHUNK_ID, "first occurrence", 0.71),
                candidate(SECOND_CHUNK_ID, "second chunk", 0.69),
                candidate(FIRST_CHUNK_ID, "later duplicate", 0.99)));
        when(fixture.chunkMapper.findActiveByPublicIds(eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), any()))
                .thenReturn(List.of(
                        chunk(SECOND_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                                ChunkStatus.ACTIVE, 1, "database second"),
                        chunk(FIRST_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                                ChunkStatus.ACTIVE, 0, "database first")));

        List<RetrievedChunk> result = fixture.service.retrieve(
                new RetrievalQuery("stars", Set.of(KNOWLEDGE_ID), 2, 0.0));

        assertEquals(List.of(FIRST_CHUNK_ID, SECOND_CHUNK_ID),
                result.stream().map(RetrievedChunk::chunkId).toList());
        assertEquals(List.of(0.71, 0.69), result.stream().map(RetrievedChunk::score).toList());
    }

    @Test
    void drops_postquery_row_without_current_enabled_file_and_knowledge_relation_proof() {
        Fixture fixture = fixture();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(candidate(FIRST_CHUNK_ID, "candidate", 0.90)));
        when(fixture.chunkMapper.findActiveByPublicIds(eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), any()))
                .thenReturn(List.of(baseChunk(FIRST_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                        ChunkStatus.ACTIVE, 0, "database content")));

        List<RetrievedChunk> result = fixture.service.retrieve(
                new RetrievalQuery("stars", Set.of(KNOWLEDGE_ID), 1, 0.0));

        assertTrue(result.isEmpty());
    }

    @Test
    void drops_active_rows_with_null_empty_or_blank_index_content() {
        Fixture fixture = fixture();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                candidate(FIRST_CHUNK_ID, "null", 0.99),
                candidate(SECOND_CHUNK_ID, "empty", 0.98),
                candidate(THIRD_CHUNK_ID, "blank", 0.97),
                candidate(FOURTH_CHUNK_ID, "valid", 0.96)));
        when(fixture.chunkMapper.findActiveByPublicIds(eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), any()))
                .thenReturn(List.of(
                        chunk(FIRST_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                                ChunkStatus.ACTIVE, 0, null),
                        chunk(SECOND_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                                ChunkStatus.ACTIVE, 1, ""),
                        chunk(THIRD_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                                ChunkStatus.ACTIVE, 2, " \n\t"),
                        chunk(FOURTH_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                                ChunkStatus.ACTIVE, 3, "database valid")));

        List<RetrievedChunk> result = fixture.service.retrieve(
                new RetrievalQuery("stars", Set.of(KNOWLEDGE_ID), 4, 0.0));

        assertEquals(List.of(FOURTH_CHUNK_ID), result.stream().map(RetrievedChunk::chunkId).toList());
        assertEquals("database valid", result.get(0).content());
    }

    @Test
    void returns_file_identity_name_and_type_from_authoritative_postquery_join() {
        Fixture fixture = fixture();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(candidate(FIRST_CHUNK_ID, "candidate", 0.90)));
        DocumentChunk current = chunk(FIRST_CHUNK_ID, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                ChunkStatus.ACTIVE, 0, "database content");
        current.setSourceDocumentPublicId(CURRENT_FILE_PUBLIC_ID);
        current.setSourceFileName("current-guide.markdown");
        current.setSourceFileType("markdown");
        current.setSourceKnowledgePublicId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
        current.setSourceKnowledgeName("当前知识库");
        when(fixture.chunkMapper.findActiveByPublicIds(eq(TENANT_ID), eq(Set.of(KNOWLEDGE_ID)), any()))
                .thenReturn(List.of(current));

        RetrievedChunk result = fixture.service.retrieve(
                new RetrievalQuery("stars", Set.of(KNOWLEDGE_ID), 1, 0.0)).get(0);

        assertEquals(CURRENT_FILE_PUBLIC_ID, result.documentId());
        assertEquals("current-guide.markdown", result.title());
        assertEquals("markdown", result.fileType());
        assertEquals(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"), result.knowledgeId());
        assertEquals("当前知识库", result.knowledgeName());
    }

    @Test
    void top_k_twenty_overfetches_sixty_without_arithmetic_or_store_overflow() {
        Fixture fixture = fixture();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        fixture.service.retrieve(new RetrievalQuery("stars", Set.of(KNOWLEDGE_ID), 20, 0.0));

        ArgumentCaptor<SearchRequest> search = ArgumentCaptor.forClass(SearchRequest.class);
        verify(fixture.vectorStore).similaritySearch(search.capture());
        assertEquals(60, search.getValue().getTopK());
    }

    @Test
    void active_chunk_query_scopes_tenant_knowledge_public_ids_and_active_status() throws Exception {
        Configuration configuration = new Configuration();
        try (var input = getClass().getResourceAsStream("/mapper/DocumentChunkMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/DocumentChunkMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("tenantId", TENANT_ID);
        parameters.put("knowledgeIds", Set.of(KNOWLEDGE_ID, 11L));
        parameters.put("publicIds", List.of(FIRST_CHUNK_ID, SECOND_CHUNK_ID));

        BoundSql bound = configuration.getMappedStatement(
                "com.starsea.ai.mapper.DocumentChunkMapper.findActiveByPublicIds")
                .getBoundSql(parameters);
        String sql = bound.getSql().replaceAll("\\s+", " ").trim().toLowerCase();

        assertTrue(sql.contains("join file f"));
        assertTrue(sql.contains("join knowledge_file kf"));
        assertTrue(sql.contains("join knowledge k"));
        assertTrue(sql.contains("k.tenant_id = dc.tenant_id"));
        assertTrue(sql.contains("k.public_id as source_knowledge_public_id"));
        assertTrue(sql.contains("dc.tenant_id = ?"));
        assertTrue(sql.contains("f.tenant_id = ?"));
        assertTrue(sql.contains("kf.tenant_id = ?"));
        assertTrue(sql.contains("dc.knowledge_id in"));
        assertTrue(sql.contains("kf.knowledge_id in"));
        assertTrue(sql.contains("dc.public_id in"));
        assertTrue(sql.contains("dc.status = 2"));
        assertTrue(sql.contains("f.status = 1"));
        assertTrue(sql.contains("dc.index_content is not null"));
        assertTrue(sql.contains("dc.index_content ~ '[^[:space:]]'"));
        assertTrue(sql.contains("left join document_chunk parent"));
        assertTrue(sql.contains("parent.id = dc.parent_chunk_id"));
        assertTrue(sql.contains("parent.tenant_id = dc.tenant_id"));
        assertTrue(sql.contains("parent.knowledge_id = dc.knowledge_id"));
        assertTrue(sql.contains("parent.file_id = dc.file_id"));
        assertTrue(sql.contains("parent.chunk_type = 1"));
        assertTrue(sql.contains("parent.status = 2"));
        assertTrue(sql.contains("dc.chunk_type in (0, 2)"));
        assertTrue(sql.contains("dc.chunk_type = 0 or parent.id is not null"));
        assertTrue(sql.contains("f.public_id as source_document_public_id"));
        assertTrue(sql.contains("f.type as source_file_type"));
        assertTrue(sql.contains("parent.public_id as parent_public_id"));
        assertTrue(sql.contains("parent.content as parent_content"));
        assertTrue(sql.contains("parent.section_path as parent_section_path"));
        assertTrue(sql.contains("parent.source_locator as parent_source_locator"));
        assertTrue(sql.contains("parent.position as parent_position"));
        assertTrue(sql.contains("parent.status as parent_status"));
        assertFalse(sql.contains("select * from document_chunk where public_id in"));
        Set<String> resultProperties = configuration.getResultMap(
                        "com.starsea.ai.mapper.DocumentChunkMapper.DocumentChunkResultMap")
                .getResultMappings().stream()
                .map(mapping -> mapping.getProperty())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(resultProperties.containsAll(Set.of(
                "sourceDocumentPublicId", "sourceFileName", "sourceFileType", "sourceKnowledgePublicId",
                "sourceKnowledgeName", "parentPublicId", "parentContent", "parentSectionPath",
                "parentSourceLocator", "parentPosition", "parentStatus")));
    }

    private Fixture fixture() {
        VectorStore vectorStore = mock(VectorStore.class);
        FileService fileService = mock(FileService.class);
        DocumentChunkMapper chunkMapper = mock(DocumentChunkMapper.class);
        File file = new File();
        file.setId(FILE_ID);
        file.setPublicId(FILE_PUBLIC_ID);
        file.setFileName("guide.md");
        file.setType("md");
        file.setStatus(1);
        when(fileService.listEnabledByKnowledgeIds(TENANT_ID, Set.of(KNOWLEDGE_ID)))
                .thenReturn(List.of(file));
        return new Fixture(vectorStore, fileService, chunkMapper,
                new PgVectorRagServiceImpl(vectorStore, fileService, chunkMapper));
    }

    private void assertCandidateRejected(Document candidate) {
        Fixture fixture = fixture();
        when(fixture.vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(candidate));

        List<RetrievedChunk> result = fixture.service.retrieve(
                new RetrievalQuery("stars", Set.of(KNOWLEDGE_ID), 1, 0.0));

        assertTrue(result.isEmpty());
        verify(fixture.chunkMapper, never()).findActiveByPublicIds(anyLong(), any(), any());
    }

    private static Document candidate(UUID publicId, String text, double score) {
        return Document.builder()
                .id(publicId.toString())
                .text(text)
                .metadata(candidateMetadata(publicId.toString()))
                .score(score)
                .build();
    }

    private static Map<String, Object> candidateMetadata(String chunkPublicId) {
        return Map.of(
                "tenantId", TENANT_ID,
                "knowledgeId", KNOWLEDGE_ID,
                "fileId", FILE_ID,
                "documentPublicId", FILE_PUBLIC_ID.toString(),
                "documentChunkId", chunkPublicId,
                "chunkIndex", 0,
                "fileType", "md",
                "sectionPath", "[\"Guide\",\"Details\"]");
    }

    private static DocumentChunk chunk(UUID publicId, long tenantId, long knowledgeId, long fileId,
                                       ChunkStatus status, int position, String indexContent) {
        DocumentChunk chunk = baseChunk(publicId, tenantId, knowledgeId, fileId,
                status, position, indexContent);
        markAuthoritativeFile(chunk);
        return chunk;
    }

    private static DocumentChunk baseChunk(UUID publicId, long tenantId, long knowledgeId, long fileId,
                                           ChunkStatus status, int position, String indexContent) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId((long) position + 1);
        chunk.setPublicId(publicId);
        chunk.setTenantId(tenantId);
        chunk.setKnowledgeId(knowledgeId);
        chunk.setFileId(fileId);
        chunk.setStatus(status.code());
        chunk.setPosition(position);
        chunk.setChunkType(ChunkType.SINGLE.code());
        chunk.setIndexContent(indexContent);
        chunk.setSectionPath(List.of("Guide", "Details"));
        chunk.setSourceLocator(Map.of("startLine", 7, "endLine", 11, "blockIds", List.of("b-1")));
        return chunk;
    }

    private static DocumentChunk childChunk(UUID publicId, UUID parentPublicId, int parentPosition,
                                            String indexContent, String parentContent) {
        DocumentChunk chunk = chunk(publicId, TENANT_ID, KNOWLEDGE_ID, FILE_ID,
                ChunkStatus.ACTIVE, parentPosition + 1, indexContent);
        chunk.setChunkType(ChunkType.CHILD.code());
        chunk.setParentChunkId(100L + parentPosition);
        chunk.setParentPublicId(parentPublicId);
        chunk.setParentContent(parentContent);
        chunk.setParentSectionPath(List.of("Parent"));
        chunk.setParentSourceLocator(Map.of("startLine", 10, "endLine", 40));
        chunk.setParentPosition(parentPosition);
        chunk.setParentStatus(ChunkStatus.ACTIVE.code());
        return chunk;
    }

    private static void markAuthoritativeFile(DocumentChunk chunk) {
        chunk.setSourceDocumentPublicId(FILE_PUBLIC_ID);
        chunk.setSourceFileName("guide.md");
        chunk.setSourceFileType("md");
    }

    private record Fixture(VectorStore vectorStore, FileService fileService,
                           DocumentChunkMapper chunkMapper, PgVectorRagServiceImpl service) {
    }
}
