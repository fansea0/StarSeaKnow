package com.starsea.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.starsea.ai.model.provider.TenantModelProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TenantModelProviderMapper extends BaseMapper<TenantModelProvider> {

    @Select("SELECT nextval('tenant_model_provider_id_seq')")
    @InterceptorIgnore(tenantLine = "true")
    long nextId();
}
