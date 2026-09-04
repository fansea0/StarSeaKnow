package com.starsea.ai.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starsea.ai.domain.ChunkVectorCleanup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ChunkVectorCleanupMapper extends BaseMapper<ChunkVectorCleanup> {

    int enqueue(@Param("vectorId") UUID vectorId,
                @Param("tenantId") long tenantId,
                @Param("knowledgeId") long knowledgeId,
                @Param("fileId") long fileId,
                @Param("chunkPublicId") UUID chunkPublicId);

    int enqueuePendingByOwner(@Param("fileId") long fileId,
                              @Param("tenantId") long tenantId,
                              @Param("knowledgeId") long knowledgeId,
                              @Param("indexingLockVersion") int indexingLockVersion);

    @InterceptorIgnore(tenantLine = "true")
    List<ChunkVectorCleanup> findDrainable(@Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    int claim(@Param("vectorId") UUID vectorId);

    @InterceptorIgnore(tenantLine = "true")
    int deleteClaimed(@Param("vectorId") UUID vectorId);

    @InterceptorIgnore(tenantLine = "true")
    int release(@Param("vectorId") UUID vectorId, @Param("lastError") String lastError);

    @InterceptorIgnore(tenantLine = "true")
    int removeActiveObligations();

    @InterceptorIgnore(tenantLine = "true")
    int resetAbandonedClaims();
}
