package com.starsea.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starsea.ai.domain.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    List<DocumentChunk> findActiveByPublicIds(@Param("tenantId") long tenantId,
                                              @Param("knowledgeIds") Set<Long> knowledgeIds,
                                              @Param("publicIds") List<UUID> publicIds);

    List<DocumentChunk> findByFile(@Param("fileId") long fileId,
                                   @Param("tenantId") long tenantId,
                                   @Param("knowledgeId") long knowledgeId);

    List<DocumentChunk> findByFileForUpdate(@Param("fileId") long fileId,
                                            @Param("tenantId") long tenantId,
                                            @Param("knowledgeId") long knowledgeId);

    int deleteReplaceableDrafts(@Param("fileId") long fileId,
                                @Param("tenantId") long tenantId,
                                @Param("knowledgeId") long knowledgeId,
                                @Param("replaceEditedDrafts") boolean replaceEditedDrafts);

    DocumentChunk findScopedByPublicIdForUpdate(@Param("fileId") long fileId,
                                                @Param("tenantId") long tenantId,
                                                @Param("knowledgeId") long knowledgeId,
                                                @Param("chunkPublicId") UUID chunkPublicId);

    DocumentChunk findNextDependentForUpdate(@Param("fileId") long fileId,
                                             @Param("tenantId") long tenantId,
                                             @Param("knowledgeId") long knowledgeId,
                                             @Param("position") int position,
                                             @Param("sourceChunkId") long sourceChunkId);

    int updateContent(@Param("fileId") long fileId,
                      @Param("tenantId") long tenantId,
                      @Param("knowledgeId") long knowledgeId,
                      @Param("chunkPublicId") UUID chunkPublicId,
                      @Param("content") String content,
                      @Param("tokenCount") int tokenCount,
                      @Param("contentHash") String contentHash,
                      @Param("lockVersion") int lockVersion);

    int invalidateDependent(@Param("fileId") long fileId,
                            @Param("tenantId") long tenantId,
                            @Param("knowledgeId") long knowledgeId,
                            @Param("chunkId") long chunkId,
                            @Param("sourceChunkId") long sourceChunkId,
                            @Param("lockVersion") int lockVersion);

    int deleteScoped(@Param("fileId") long fileId,
                     @Param("tenantId") long tenantId,
                     @Param("knowledgeId") long knowledgeId,
                     @Param("chunkPublicId") UUID chunkPublicId,
                     @Param("lockVersion") int lockVersion);
}
