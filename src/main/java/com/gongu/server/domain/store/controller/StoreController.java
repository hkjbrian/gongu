package com.gongu.server.domain.store.controller;

import com.gongu.server.domain.store.dto.response.StoreResponse;
import com.gongu.server.domain.store.service.StoreService;
import com.gongu.server.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<StoreResponse>>> getStores(
            @PageableDefault(size = 20) Pageable pageable) {
        Page<StoreResponse> stores = storeService.getStores(pageable);
        return ResponseEntity.ok(ApiResponse.success(stores));
    }

    @GetMapping("/{storeId}")
    public ResponseEntity<ApiResponse<StoreResponse>> getStore(@PathVariable Long storeId) {
        StoreResponse store = storeService.getStore(storeId);
        return ResponseEntity.ok(ApiResponse.success(store));
    }
}
