package com.starsea.ai.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starsea.ai.domain.ChunkVectorCleanup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Mapper
public interface ChunkVectorCleanupMapper extends BaseMapper<ChunkVectorCleanup> {

    int enqueue(@Param("vectorId") UUID vectorId,
                @Param("tenantId") long tenantId,
                @Param("knowledgeId") long knowledgeId,
                @Param("fileId") long fileId,
                @Param("chunkPublicId") UUID chunkPublicId);

    int startWriter(@Param("vectorId") UUID vectorId,
                    @Param("tenantId") long tenantId,
                    @Param("knowledgeId") long knowledgeId,
                    @Param("fileId") long fileId,
                    @Param("chunkPublicId") UUID chunkPublicId,
                    @Param("writerOwner") UUID writerOwner,
                    @Param("leaseSeconds") int leaseSeconds);

    int finishWriter(@Param("vectorId") UUID vectorId,
                     @Param("tenantId") long tenantId,
                     @Param("knowledgeId") long knowledgeId,
                     @Param("fileId") long fileId,
                     @Param("chunkPublicId") UUID chunkPublicId,
                     @Param("writerOwner") UUID writerOwner);

    @InterceptorIgnore(tenantLine = "true")
    int renewWriterLeases(@Param("writerOwner") UUID writerOwner,
                          @Param("vectorIds") Set<UUID> vectorIds,
                          @Param("leaseSeconds") int leaseSeconds);

    int enqueuePendingByOwner(@Param("fileId") long fileId,
                              @Param("tenantId") long tenantId,
                              @Param("knowledgeId") long knowledgeId,
                              @Param("indexingLockVersion") int indexingLockVersion);

    @InterceptorIgnore(tenantLine = "true")
    List<ChunkVectorCleanup> claimDue(@Param("claimOwner") UUID claimOwner,
                                      @Param("leaseSeconds") int leaseSeconds,
                                      @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    ChunkVectorCleanup lockClaimedForDelete(@Param("vectorId") UUID vectorId,
                                            @Param("claimOwner") UUID claimOwner);

    @InterceptorIgnore(tenantLine = "true")
    int deferForWriter(@Param("vectorId") UUID vectorId,
                       @Param("claimOwner") UUID claimOwner);

    @InterceptorIgnore(tenantLine = "true")
    int deleteClaimed(@Param("vectorId") UUID vectorId,
                      @Param("claimOwner") UUID claimOwner);

    @InterceptorIgnore(tenantLine = "true")
    int release(@Param("vectorId") UUID vectorId,
                @Param("claimOwner") UUID claimOwner,
                @Param("lastError") String lastError);

    @InterceptorIgnore(tenantLine = "true")
    int reclaimExpiredClaims();

    @InterceptorIgnore(tenantLine = "true")
    int resetAbandonedClaims();

    @InterceptorIgnore(tenantLine = "true")
    long countUnprotectedStale(@Param("tenantId") long tenantId,
                               @Param("knowledgeIds") Set<Long> knowledgeIds);
}
