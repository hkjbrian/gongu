package com.gongu.server.domain.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongu.server.domain.store.dto.request.RegisterMemberStoreRequest;
import com.gongu.server.domain.store.dto.response.RegisterMemberStoreResponse;
import com.gongu.server.domain.store.service.StoreService;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.StoreErrorCode;
import com.gongu.server.global.security.Role;
import com.gongu.server.global.security.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemberController.class)
@AutoConfigureMockMvc(addFilters = false)
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private StoreService storeService;

    /**
     * addFilters=false 환경에서는 SecurityMockMvcRequestPostProcessors.authentication()이
     * SecurityContextHolder에 반영되지 않으므로, RequestPostProcessor에서 직접 설정한다.
     */
    private RequestPostProcessor asMember(Long memberId) {
        return (MockHttpServletRequest request) -> {
            UserPrincipal principal = new UserPrincipal(memberId, Role.MEMBER);
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))
            );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(auth);
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    @Test
    @DisplayName("POST /members/me/stores 정상 요청 → 201 + storeId, storeName, isPreferred")
    void registerMemberStore_정상_201() throws Exception {
        // given
        Long memberId = 1L;
        RegisterMemberStoreRequest request = new RegisterMemberStoreRequest(2L, false);
        RegisterMemberStoreResponse response = new RegisterMemberStoreResponse(2L, "테스트매장", false);

        given(storeService.registerMemberStore(eq(memberId), any(RegisterMemberStoreRequest.class)))
                .willReturn(response);

        // when & then
        mockMvc.perform(post("/members/me/stores")
                        .with(asMember(memberId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.storeId").value(2))
                .andExpect(jsonPath("$.data.storeName").value("테스트매장"))
                .andExpect(jsonPath("$.data.isPreferred").value(false));
    }

    @Test
    @DisplayName("POST /members/me/stores 중복 요청 → 409 + code: STORE_002")
    void registerMemberStore_중복_409() throws Exception {
        // given
        Long memberId = 1L;
        RegisterMemberStoreRequest request = new RegisterMemberStoreRequest(2L, false);

        given(storeService.registerMemberStore(eq(memberId), any(RegisterMemberStoreRequest.class)))
                .willThrow(new BusinessException(StoreErrorCode.MEMBER_STORE_DUPLICATE));

        // when & then
        mockMvc.perform(post("/members/me/stores")
                        .with(asMember(memberId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_002"));
    }

    @Test
    @DisplayName("POST /members/me/stores storeId 누락 → 400")
    void registerMemberStore_storeId_누락_400() throws Exception {
        // given — storeId 없이 isPreferred만 전송
        String requestBody = "{\"isPreferred\": true}";

        // when & then
        mockMvc.perform(post("/members/me/stores")
                        .with(asMember(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }
}
