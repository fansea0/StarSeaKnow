package com.starsea.ai.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starsea.ai.domain.PlatformInvitation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface PlatformInvitationMapper extends BaseMapper<PlatformInvitation> {

    @Update("""
            UPDATE platform_invitation
            SET status = 'USED', used_at = #{usedAt}, used_tenant_id = #{tenantId}, used_user_id = #{userId},
                update_time = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND status = 'ACTIVE'
              AND used_at IS NULL
              AND valid_from <= CURRENT_TIMESTAMP
              AND valid_until >= CURRENT_TIMESTAMP
            """)
    int consumeIfAvailable(@Param("id") long id,
                           @Param("usedAt") OffsetDateTime usedAt,
                           @Param("tenantId") long tenantId,
                           @Param("userId") long userId);
}
