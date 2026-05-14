package com.gongu.server.domain.product.service;

import com.gongu.server.domain.product.domain.Product;
import com.gongu.server.domain.product.domain.ProductStatus;
import com.gongu.server.domain.product.dto.CreateProductRequest;
import com.gongu.server.domain.product.dto.ProductDetailResponse;
import com.gongu.server.domain.product.dto.ProductSummaryResponse;
import com.gongu.server.domain.product.dto.UpdateProductRequest;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.store.entity.MemberStore;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.entity.StoreAdmin;
import com.gongu.server.domain.store.repository.MemberStoreRepository;
import com.gongu.server.domain.store.repository.StoreAdminRepository;
import com.gongu.server.domain.store.repository.StoreRepository;
import com.gongu.server.domain.user.entity.Member;
import com.gongu.server.domain.user.repository.MemberRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import com.gongu.server.global.exception.errorcode.StoreErrorCode;
import com.gongu.server.global.exception.errorcode.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final MemberRepository memberRepository;
    private final MemberStoreRepository memberStoreRepository;
    private final StoreRepository storeRepository;
    private final StoreAdminRepository storeAdminRepository;

    // 회원: 상품 목록 조회
    public Page<ProductSummaryResponse> getProducts(Long memberId, Long storeId, Pageable pageable) {
        Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        if (storeId != null) {
            Store store = storeRepository.findByIdAndDeletedAtIsNull(storeId)
                    .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_NOT_FOUND));

            // 회원이 해당 매장에 가입됐는지 검증 (보안상 미가입 매장은 존재하지 않는 것처럼 처리)
            if (!memberStoreRepository.existsByMemberAndStore(member, store)) {
                throw new BusinessException(StoreErrorCode.STORE_NOT_FOUND);
            }

            return productRepository.findAllByStoreAndStatus(store, ProductStatus.ACTIVE, pageable)
                    .map(ProductSummaryResponse::from);
        }

        // storeId 없는 경우: 가입한 모든 매장의 ACTIVE 상품 목록
        List<MemberStore> memberStores = memberStoreRepository.findAllByMember(member);
        if (memberStores.isEmpty()) {
            return Page.empty(pageable);
        }

        List<Store> stores = memberStores.stream()
                .map(MemberStore::getStore)
                .toList();

        return productRepository.findAllByStoreInAndStatus(stores, ProductStatus.ACTIVE, pageable)
                .map(ProductSummaryResponse::from);
    }

    // 회원: 상품 상세 조회
    public ProductDetailResponse getProduct(Long memberId, Long productId) {
        Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));

        // 상품 매장에 회원이 가입됐는지 검증 (보안 정보 노출 방지: 미가입 매장 상품도 존재하지 않는 것처럼 처리)
        if (!memberStoreRepository.existsByMemberAndStore(member, product.getStore())) {
            throw new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND);
        }

        return ProductDetailResponse.from(product);
    }

    // 관리자: 상품 목록 조회
    public Page<ProductSummaryResponse> getAdminProducts(Long storeAdminId, Pageable pageable) {
        StoreAdmin storeAdmin = storeAdminRepository.findByIdAndDeletedAtIsNull(storeAdminId)
                .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_ADMIN_NOT_FOUND));

        Store store = storeAdmin.getStore();

        return productRepository.findAllByStore(store, pageable)
                .map(ProductSummaryResponse::from);
    }

    // 관리자: 상품 상세 조회
    public ProductDetailResponse getAdminProduct(Long storeAdminId, Long productId) {
        StoreAdmin storeAdmin = storeAdminRepository.findByIdAndDeletedAtIsNull(storeAdminId)
                .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_ADMIN_NOT_FOUND));

        Store store = storeAdmin.getStore();

        // findByIdAndStore: 소속 매장 검증을 DB에서 한 번에 처리 (추가 Store SELECT 제거)
        Product product = productRepository.findByIdAndStore(productId, store)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));

        return ProductDetailResponse.from(product);
    }

    // 관리자: 상품 등록
    @Transactional
    public ProductDetailResponse createProduct(Long storeAdminId, CreateProductRequest request) {
        StoreAdmin storeAdmin = storeAdminRepository.findByIdAndDeletedAtIsNull(storeAdminId)
                .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_ADMIN_NOT_FOUND));

        Store store = storeAdmin.getStore();

        Product product = Product.create(store, request.name(), request.description(),
                request.price(), request.totalStock(), ProductStatus.UPCOMING,
                request.startAt(), request.endAt());
        productRepository.save(product);

        return ProductDetailResponse.from(product);
    }

    // 관리자: 상품 수정
    @Transactional
    public ProductDetailResponse updateProduct(Long storeAdminId, Long productId, UpdateProductRequest request) {
        StoreAdmin storeAdmin = storeAdminRepository.findByIdAndDeletedAtIsNull(storeAdminId)
                .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_ADMIN_NOT_FOUND));

        Store store = storeAdmin.getStore();

        Product product = productRepository.findByIdAndStore(productId, store)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));

        product.update(request.name(), request.description(), request.price(),
                request.totalStock(), request.startAt(), request.endAt());

        return ProductDetailResponse.from(product);
    }

    // 재고 차감 (비관적 락)
    @Transactional
    public void decreaseStock(Long productId, int quantity) {
        Product product = productRepository.findByIdWithLock(productId)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
        product.decreaseStock(quantity);
    }

    // 관리자: 상품 종료 (소프트 클로즈)
    @Transactional
    public void closeProduct(Long storeAdminId, Long productId) {
        StoreAdmin storeAdmin = storeAdminRepository.findByIdAndDeletedAtIsNull(storeAdminId)
                .orElseThrow(() -> new BusinessException(StoreErrorCode.STORE_ADMIN_NOT_FOUND));

        Store store = storeAdmin.getStore();

        Product product = productRepository.findByIdAndStore(productId, store)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));

        product.close();
    }
}
