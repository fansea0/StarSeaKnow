package com.starsea.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starsea.ai.domain.FileTextExtraction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FileTextExtractionMapper extends BaseMapper<FileTextExtraction> {

    FileTextExtraction findScoped(@Param("tenantId") Long tenantId,
                                  @Param("fileId") Long fileId);

    int updateScoped(FileTextExtraction extraction);
}
