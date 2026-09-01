package com.starsea.ai.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starsea.ai.domain.File;
import com.starsea.ai.domain.vo.FileVo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;


/**
* @author ASUS
* @description 针对表【file】的数据库操作Mapper
* @createDate 2025-05-03 11:33:17
*/
@Mapper
public interface FileMapper extends BaseMapper<File> {
    File selectByFileId(Long id);

    List<FileVo> selectByKnowledgeId(@Param("tenantId") Long tenantId,
                                     @Param("knowledgeId") Long knowledgeId);

    List<File> selectEnabledByKnowledgeIds(@Param("tenantId") Long tenantId,
                                           @Param("knowledgeIds") Set<Long> knowledgeIds);

}


