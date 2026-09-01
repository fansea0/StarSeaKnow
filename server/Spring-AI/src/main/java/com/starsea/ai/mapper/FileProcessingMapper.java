package com.starsea.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starsea.ai.domain.FileProcessing;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FileProcessingMapper extends BaseMapper<FileProcessing> {

    FileProcessing findScopedForUpdate(@Param("fileId") long fileId,
                                       @Param("tenantId") long tenantId,
                                       @Param("knowledgeId") long knowledgeId);

    int transition(@Param("fileId") long fileId,
                   @Param("tenantId") long tenantId,
                   @Param("knowledgeId") long knowledgeId,
                   @Param("expected") int expected,
                   @Param("target") int target,
                   @Param("progress") int progress,
                   @Param("lockVersion") int lockVersion,
                   @Param("failedFromState") Integer failedFromState,
                   @Param("lastError") String lastError);
}
