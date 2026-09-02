package com.starsea.ai.auth;

import com.starsea.ai.domain.AppUser;
import com.starsea.ai.domain.dto.AjaxResult;
import com.starsea.ai.mapper.AppUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantMemberControllerTest {

    @AfterEach
    void clearAuthContext() {
        AuthContext.clear();
    }

    @Test
    void lists_member_who_has_never_logged_in() {
        AppUserMapper users = mock(AppUserMapper.class);
        AppUser member = new AppUser();
        member.setId(863L);
        member.setTenantId(974L);
        member.setUsername("root");
        member.setDisplayName("root");
        member.setRole("tenant_admin");
        member.setStatus(1);
        member.setLastLoginAt(null);
        when(users.selectList(any())).thenReturn(List.of(member));
        AuthContext.set(new AuthContext(
                AuthContext.Kind.BUSINESS, 863L, 974L, "tenant_admin", "test-jti"));
        TenantMemberController controller = new TenantMemberController(
                users, mock(PasswordEncoder.class));

        AjaxResult result = controller.list();

        assertThat(result.get(AjaxResult.DATA_TAG)).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) result.get(AjaxResult.DATA_TAG);
        assertThat(data).hasSize(1);
        assertThat(data.get(0))
                .containsEntry("id", 863L)
                .containsEntry("username", "root")
                .containsKey("lastLoginAt");
        assertThat(data.get(0).get("lastLoginAt")).isNull();
    }
}
