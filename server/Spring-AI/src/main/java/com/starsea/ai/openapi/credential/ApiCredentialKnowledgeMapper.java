package com.starsea.ai.openapi.credential;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface ApiCredentialKnowledgeMapper extends BaseMapper<ApiCredentialKnowledge> {
}
