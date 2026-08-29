package com.fansea.ai.openapi.retrieval;

import com.fansea.ai.auth.AuthContext;
import com.fansea.ai.domain.File;
import com.fansea.ai.service.FileService;
import com.fansea.ai.service.KnowledgeFileService;
import com.fansea.ai.service.impl.PgVectorRagServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgVectorRagServiceImplTest {

    private static final UUID FILE_A_PUBLIC_ID = UUID.fromString("10000000-0000-0000-0000-000000000031");
    private static final UUID FILE_B_PUBLIC_ID = UUID.fromString("10000000-0000-0000-0000-000000000032");
    private static final UUID CHUNK_A_ID = UUID.fromString("20000000-0000-0000-0000-000000000031");
    private static final UUID CHUNK_B_ID = UUID.fromString("20000000-0000-0000-0000-000000000032");

    private VectorStore vectorStore;
    private KnowledgeFileService knowledgeFileService;
    private FileService fileService;
    private PgVectorRagServiceImpl service;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        knowledgeFileService = mock(KnowledgeFileService.class);
        fileService = mock(FileService.class);
        service = new PgVectorRagServiceImpl(vectorStore, knowledgeFileService, fileService);
        AuthContext.set(new AuthContext(AuthContext.Kind.EXTERNAL_API, 41L, 7L, "external_api", null));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void searchesAllAuthorizedKnowledgeBasesOnceAndReturnsGlobalTopK() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document("chunk-a", 0.91, 31L, CHUNK_A_ID, 3, 4),
                document("chunk-b", 0.77, 32L, CHUNK_B_ID, 5, 6)));
        when(fileService.listEnabledByKnowledgeIds(eq(7L), any())).thenReturn(List.of(
                file(31L, FILE_A_PUBLIC_ID, "refund-a.pdf", "pdf", 1),
                file(32L, FILE_B_PUBLIC_ID, "refund-b.pdf", "pdf", 1)));

        List<RetrievedChunk> result = service.retrieve(
                new RetrievalQuery("退款材料", Set.of(12L, 11L), 5, 0.5));

        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(1)).similaritySearch(request.capture());
        verify(fileService, times(1)).listEnabledByKnowledgeIds(7L, Set.of(11L, 12L));
        assertThat(request.getValue().getQuery()).isEqualTo("退款材料");
        assertThat(request.getValue().getTopK()).isEqualTo(5);
        assertThat(request.getValue().getSimilarityThreshold()).isEqualTo(0.5);
        assertThat(request.getValue().getFilterExpression().toString())
                .contains("knowledgeId", "11", "12", "tenantId", "7")
                .containsSubsequence("11", "12");
        assertThat(result).extracting(RetrievedChunk::score).containsExactly(0.91, 0.77);
    }

    @Test
    void rejectsEmptyKnowledgeScopeBeforeVectorSearch() {
        assertThatThrownBy(() -> service.retrieve(new RetrievalQuery("退款材料", Set.of(), 5, 0.5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("knowledge");

        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
        verify(fileService, never()).listEnabledByKnowledgeIds(any(), any());
        verify(fileService, never()).listByIds(any());
    }

    @Test
    void filtersDisabledFileIdsBeforeVectorTopKAndExcludesDefensiveStaleHit() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document("enabled", 0.9, 31L, CHUNK_A_ID, 1, 0),
                document("disabled", 0.8, 32L, CHUNK_B_ID, 2, 1)));
        when(fileService.listEnabledByKnowledgeIds(eq(7L), any())).thenReturn(List.of(
                file(31L, FILE_A_PUBLIC_ID, "enabled.pdf", "pdf", 1)));

        List<RetrievedChunk> result = service.retrieve(
                new RetrievalQuery("refund", Set.of(11L), 5, 0.0));

        assertThat(result).extracting(RetrievedChunk::content).containsExactly("enabled");
        assertThat(result).extracting(RetrievedChunk::title).doesNotContain("disabled-secret.pdf");
        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().getFilterExpression().toString())
                .contains("fileId", "31")
                .doesNotContain("32");
    }

    @Test
    void supportsMaximumTopKAndClampsScoresToPublicRange() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document("above", 1.2, 31L, CHUNK_A_ID, 1, 0),
                document("below", -0.2, 32L, CHUNK_B_ID, 2, 1)));
        when(fileService.listEnabledByKnowledgeIds(eq(7L), any())).thenReturn(List.of(
                file(31L, FILE_A_PUBLIC_ID, "a.pdf", "pdf", 1),
                file(32L, FILE_B_PUBLIC_ID, "b.pdf", "pdf", 1)));

        List<RetrievedChunk> result = service.retrieve(
                new RetrievalQuery("refund", Set.of(11L), 20, 0.0));

        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().getTopK()).isEqualTo(20);
        assertThat(result).extracting(RetrievedChunk::score).containsExactly(1.0, 0.0);
    }

    @Test
    void removesNullScoreAfterNormalizingItToZeroBelowThreshold() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document("no-score", null, 31L, CHUNK_A_ID, 1, 0),
                document("scored", 0.6, 32L, CHUNK_B_ID, 2, 1)));
        when(fileService.listEnabledByKnowledgeIds(eq(7L), any())).thenReturn(List.of(
                file(31L, FILE_A_PUBLIC_ID, "a.pdf", "pdf", 1),
                file(32L, FILE_B_PUBLIC_ID, "b.pdf", "pdf", 1)));

        List<RetrievedChunk> result = service.retrieve(
                new RetrievalQuery("refund", Set.of(11L), 5, 0.5));

        assertThat(result).extracting(RetrievedChunk::content).containsExactly("scored");
        assertThat(result).extracting(RetrievedChunk::score).containsExactly(0.6);
    }

    @Test
    void batchEnrichesStableSourceMetadataFromCurrentFileRows() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document("chunk-a", 0.91, 31L, CHUNK_A_ID, 3, 4),
                document("chunk-b", 0.77, 32L, CHUNK_B_ID, 5, 6)));
        when(fileService.listEnabledByKnowledgeIds(eq(7L), any())).thenReturn(List.of(
                file(31L, FILE_A_PUBLIC_ID, "current-a.pdf", "pdf", 1),
                file(32L, FILE_B_PUBLIC_ID, "current-b.docx", "docx", 1)));

        List<RetrievedChunk> result = service.retrieve(
                new RetrievalQuery("refund", Set.of(11L), 5, 0.0));

        verify(fileService, times(1)).listEnabledByKnowledgeIds(eq(7L), eq(Set.of(11L)));
        verify(fileService, never()).listByIds(any());
        assertThat(result).containsExactly(
                new RetrievedChunk("chunk-a", 0.91, "current-a.pdf", FILE_A_PUBLIC_ID,
                        CHUNK_A_ID, "pdf", 3, 4),
                new RetrievedChunk("chunk-b", 0.77, "current-b.docx", FILE_B_PUBLIC_ID,
                        CHUNK_B_ID, "docx", 5, 6));
    }

    @Test
    void dropsStaleVectorFileOutsideTenantAndAuthorizedKnowledgeWithoutUnscopedLookup() {
        UUID staleChunkId = UUID.fromString("20000000-0000-0000-0000-000000000099");
        when(fileService.listEnabledByKnowledgeIds(eq(7L), eq(Set.of(11L))))
                .thenReturn(List.of(file(31L, FILE_A_PUBLIC_ID, "allowed.pdf", "pdf", 1)));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document("foreign secret", 0.99, 99L, staleChunkId, 1, 0)));

        List<RetrievedChunk> result = service.retrieve(
                new RetrievalQuery("refund", Set.of(11L), 5, 0.0));

        assertThat(result).isEmpty();
        verify(fileService, never()).listByIds(any());
    }

    @Test
    void skipsVectorSearchWhenAuthorizedScopeHasNoEnabledFiles() {
        when(fileService.listEnabledByKnowledgeIds(eq(7L), eq(Set.of(11L))))
                .thenReturn(List.of());

        List<RetrievedChunk> result = service.retrieve(
                new RetrievalQuery("refund", Set.of(11L), 5, 0.0));

        assertThat(result).isEmpty();
        verify(vectorStore, never()).similaritySearch(any(SearchRequest.class));
    }

    @Test
    void retrievalQueryDefensivelyCopiesScopeAndRejectsInvalidLimits() {
        Set<Long> mutableScope = new java.util.HashSet<>(Set.of(11L));
        RetrievalQuery query = new RetrievalQuery("refund", mutableScope, 5, 0.5);
        mutableScope.add(12L);

        assertThat(query.knowledgeIds()).containsExactly(11L);
        assertThatThrownBy(() -> new RetrievalQuery("refund", Set.of(11L), 0, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalQuery("refund", Set.of(11L), 21, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalQuery("refund", Set.of(11L), 5, -0.1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetrievalQuery("refund", Set.of(11L), 5, 1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void vectorizeAddsStableChunkMetadataWithoutFilePath(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("source.txt");
        Files.writeString(source, "Refunds require an order number and proof of payment.");
        File file = file(31L, FILE_A_PUBLIC_ID, "refunds.txt", "txt", 1);
        file.setPath(source.toString());
        ArgumentCaptor<List<Document>> documents = listCaptor();

        service.vectorize(file, 11L);

        verify(vectorStore).accept(documents.capture());
        assertThat(documents.getValue()).isNotEmpty().allSatisfy(document -> {
            assertThat(document.getMetadata())
                    .containsEntry("tenantId", 7L)
                    .containsEntry("knowledgeId", 11L)
                    .containsEntry("fileId", 31L)
                    .containsEntry("documentPublicId", FILE_A_PUBLIC_ID.toString())
                    .containsEntry("chunkId", document.getId())
                    .containsEntry("fileType", "txt")
                    .containsKey("chunkIndex");
            assertThat(document.getMetadata().values()).doesNotContain(source.toString());
        });
    }

    private static Document document(String content, Double score, Long fileId, UUID chunkId,
                                     Integer pageNumber, Integer chunkIndex) {
        return Document.builder()
                .id(chunkId.toString())
                .text(content)
                .score(score)
                .metadata("fileId", fileId)
                .metadata("chunkId", chunkId.toString())
                .metadata("page_number", pageNumber)
                .metadata("chunkIndex", chunkIndex)
                .build();
    }

    private static File file(Long id, UUID publicId, String name, String type, Integer status) {
        File file = new File(id, name, 100L, status, type, "/private/" + name,
                1, null, null);
        file.setPublicId(publicId);
        return file;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<Document>> listCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
