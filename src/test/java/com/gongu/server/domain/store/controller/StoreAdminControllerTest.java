package com.gongu.server.domain.store.controller;

import com.gongu.server.domain.store.dto.response.AdminUserResponse;
import com.gongu.server.domain.store.service.StoreAdminService;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.StoreErrorCode;
import com.gongu.server.global.security.Role;
import com.gongu.server.global.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StoreAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class StoreAdminControllerTest {

    @TestConfiguration
    @EnableMethodSecurity
    static class SecurityConfig {}


    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreAdminService storeAdminService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private RequestPostProcessor asStoreAdmin(Long storeAdminId) {
        return (MockHttpServletRequest request) -> {
            UserPrincipal principal = new UserPrincipal(storeAdminId, Role.STORE_ADMIN);
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_STORE_ADMIN"))
            );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    private RequestPostProcessor asUser(Long userId) {
        return (MockHttpServletRequest request) -> {
            UserPrincipal principal = new UserPrincipal(userId, Role.USER);
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    @Test
    @DisplayName("GET /admin/users 정상_200")
    void getUsers_정상_200() throws Exception {
        // given
        Long storeAdminId = 1L;
        AdminUserResponse response1 = new AdminUserResponse(10L, "홍길동", "010-1111-2222", LocalDateTime.of(2024, 1, 1, 0, 0));
        AdminUserResponse response2 = new AdminUserResponse(11L, "김철수", "010-3333-4444", LocalDateTime.of(2024, 2, 1, 0, 0));
        Page<AdminUserResponse> page = new PageImpl<>(
                List.of(response1, response2),
                PageRequest.of(0, 20),
                2
        );

        given(storeAdminService.getUsers(eq(storeAdminId), isNull(), any())).willReturn(page);

        // when & then
        mockMvc.perform(get("/admin/users")
                        .with(asStoreAdmin(storeAdminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].name").value("홍길동"))
                .andExpect(jsonPath("$.data.content[1].name").value("김철수"))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    @DisplayName("GET /admin/users?name=홍 필터_200")
    void getUsers_이름_필터_200() throws Exception {
        // given
        Long storeAdminId = 1L;
        AdminUserResponse response = new AdminUserResponse(10L, "홍길동", "010-1111-2222", LocalDateTime.of(2024, 1, 1, 0, 0));
        Page<AdminUserResponse> page = new PageImpl<>(
                List.of(response),
                PageRequest.of(0, 20),
                1
        );

        given(storeAdminService.getUsers(eq(storeAdminId), eq("홍"), any())).willReturn(page);

        // when & then
        mockMvc.perform(get("/admin/users")
                        .param("name", "홍")
                        .with(asStoreAdmin(storeAdminId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].name").value("홍길동"));
    }

    @Test
    @DisplayName("GET /admin/users 존재하지 않는 매장 관리자 → 404")
    void getUsers_STORE_ADMIN_NOT_FOUND_404() throws Exception {
        // given
        Long storeAdminId = 999L;

        given(storeAdminService.getUsers(eq(storeAdminId), isNull(), any()))
                .willThrow(new BusinessException(StoreErrorCode.STORE_ADMIN_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/admin/users")
                        .with(asStoreAdmin(storeAdminId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_003"));
    }

    @Test
    @DisplayName("GET /admin/users 인증 없는 요청 → 401")
    void getUsers_인증없음_401() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /admin/users ROLE_STORE_ADMIN 없는 요청 → 403")
    void getUsers_권한없음_403() throws Exception {
        mockMvc.perform(get("/admin/users")
                        .with(asUser(1L)))
                .andExpect(status().isForbidden());
    }
}
