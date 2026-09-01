package com.starsea.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starsea.ai.domain.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

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
}
