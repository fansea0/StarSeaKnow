package com.fansea.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fansea.ai.domain.Tenant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {}
