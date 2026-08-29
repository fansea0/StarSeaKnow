package com.starsea.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starsea.ai.domain.RefreshToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {
    int revokeById(@Param("id") long id);
    int revokeByFamily(@Param("familyId") java.util.UUID familyId);
}
