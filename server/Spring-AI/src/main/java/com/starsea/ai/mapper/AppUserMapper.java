package com.starsea.ai.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starsea.ai.domain.AppUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, tenant_id, username, password_hash, display_name, role, status, must_change_password, last_login_at, create_time, update_time " +
            "FROM app_user WHERE username = #{username}")
    AppUser selectByUsername(@Param("username") String username);

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, tenant_id, username, password_hash, display_name, role, status, must_change_password, last_login_at, create_time, update_time " +
            "FROM app_user WHERE id = #{id}")
    AppUser selectByIdForRefresh(@Param("id") long id);

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, tenant_id, username, password_hash, display_name, role, status, must_change_password, last_login_at, create_time, update_time " +
            "FROM app_user WHERE id = #{id}")
    AppUser selectByIdForPlatform(@Param("id") long id);

    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT id, tenant_id, username, password_hash, display_name, role, status, must_change_password, last_login_at, create_time, update_time " +
            "FROM app_user WHERE tenant_id = #{tenantId} ORDER BY id")
    java.util.List<AppUser> selectByTenantIdForPlatform(@Param("tenantId") long tenantId);

    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE app_user SET must_change_password = TRUE " +
            "WHERE id = #{userId} AND tenant_id = #{tenantId}")
    int markMustChangePasswordForPlatform(@Param("userId") long userId,
                                          @Param("tenantId") long tenantId);
}
