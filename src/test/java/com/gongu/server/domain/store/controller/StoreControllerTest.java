package com.gongu.server.domain.store.controller;

import com.gongu.server.domain.store.dto.response.StoreResponse;
import com.gongu.server.domain.store.service.StoreService;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.StoreErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StoreController.class)
@AutoConfigureMockMvc(addFilters = false)   // Security 필터 제외 — HTTP 로직 단위 테스트
class StoreControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoreService storeService;

    @Test
    @DisplayName("GET /stores — 200 + Page 구조 확인")
    void getStores_200_Page구조확인() throws Exception {
        // given
        StoreResponse response1 = new StoreResponse(1L, "매장1", "서울시 강남구", "02-1234-5678");
        StoreResponse response2 = new StoreResponse(2L, "매장2", "서울시 서초구", "02-9876-5432");
        Page<StoreResponse> page = new PageImpl<>(
                List.of(response1, response2),
                PageRequest.of(0, 20),
                2
        );
        given(storeService.getStores(any())).willReturn(page);

        // when & then
        mockMvc.perform(get("/stores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].name").value("매장1"))
                .andExpect(jsonPath("$.data.content[1].name").value("매장2"));
    }

    @Test
    @DisplayName("GET /stores/{id} 존재 — 200 + StoreResponse 필드 확인")
    void getStore_존재_200_필드확인() throws Exception {
        // given
        StoreResponse response = new StoreResponse(1L, "테스트매장", "서울시 마포구", "02-1111-2222");
        given(storeService.getStore(1L)).willReturn(response);

        // when & then
        mockMvc.perform(get("/stores/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.name").value("테스트매장"))
                .andExpect(jsonPath("$.data.address").value("서울시 마포구"))
                .andExpect(jsonPath("$.data.phone").value("02-1111-2222"));
    }

    @Test
    @DisplayName("GET /stores/{id} 없음 — 404 + code: STORE_001 확인")
    void getStore_없음_404_STORE_001() throws Exception {
        // given
        given(storeService.getStore(999L)).willThrow(new BusinessException(StoreErrorCode.STORE_NOT_FOUND));

        // when & then
        mockMvc.perform(get("/stores/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_001"));
    }
}
