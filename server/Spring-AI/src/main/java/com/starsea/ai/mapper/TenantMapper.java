package com.starsea.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starsea.ai.domain.Tenant;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {}
