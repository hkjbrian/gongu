package com.gongu.server.domain.product.service;

import com.gongu.server.domain.product.dto.CreateProductRequest;
import com.gongu.server.domain.product.dto.ProductDetailResponse;
import com.gongu.server.domain.product.dto.ProductSummaryResponse;
import com.gongu.server.domain.product.dto.UpdateProductRequest;
import com.gongu.server.domain.product.entity.Product;
import com.gongu.server.domain.product.entity.ProductStatus;
import com.gongu.server.domain.product.repository.ProductRepository;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.entity.StoreAdmin;
import com.gongu.server.domain.store.entity.UserStore;
import com.gongu.server.domain.store.repository.StoreAdminRepository;
import com.gongu.server.domain.store.repository.StoreRepository;
import com.gongu.server.domain.store.repository.UserStoreRepository;
import com.gongu.server.domain.user.entity.User;
import com.gongu.server.domain.user.repository.UserRepository;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import com.gongu.server.global.exception.errorcode.StoreErrorCode;
import com.gongu.server.global.exception.errorcode.UserErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserStoreRepository userStoreRepository;

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private StoreAdminRepository storeAdminRepository;

    @InjectMocks
    private ProductService productService;

    private final LocalDateTime startAt = LocalDateTime.of(2026, 6, 1, 10, 0);
    private final LocalDateTime endAt = LocalDateTime.of(2026, 6, 2, 10, 0);

    @Test
    @DisplayName("getProduct_가입_매장_상품_상세_반환")
    void getProduct_가입_매장_상품_상세_반환() {
        // given
        User user = createUser();
        Store store = createStore("테스트매장");
        Product product = createProduct(store, "테스트상품", ProductStatus.ACTIVE);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(productRepository.findById(1L)).willReturn(Optional.of(product));
        given(userStoreRepository.existsByUserAndStore(user, store)).willReturn(true);

        // when
        ProductDetailResponse response = productService.getProduct(1L, 1L);

        // then
        assertThat(response.name()).isEqualTo("테스트상품");
        assertThat(response.description()).isEqualTo("상품 설명");
        assertThat(response.price()).isEqualTo(10_000L);
        assertThat(response.status()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    @DisplayName("getProduct_존재하지_않는_회원_USER_NOT_FOUND_예외")
    void getProduct_존재하지_않는_회원_USER_NOT_FOUND_예외() {
        // given
        given(userRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.getProduct(999L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("getProduct_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외")
    void getProduct_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외() {
        // given
        User user = createUser();

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(productRepository.findById(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.getProduct(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("getProduct_미가입_매장_상품_PRODUCT_NOT_FOUND_예외")
    void getProduct_미가입_매장_상품_PRODUCT_NOT_FOUND_예외() {
        // given
        User user = createUser();
        Store store = createStore("테스트매장");
        Product product = createProduct(store, "테스트상품", ProductStatus.ACTIVE);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(productRepository.findById(1L)).willReturn(Optional.of(product));
        given(userStoreRepository.existsByUserAndStore(user, store)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> productService.getProduct(1L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("getAdminProduct_관리자_상품_상세_반환")
    void getAdminProduct_관리자_상품_상세_반환() {
        // given
        Store store = createStore("테스트매장");
        StoreAdmin storeAdmin = createStoreAdmin(store);
        Product product = createProduct(store, "관리자상품", ProductStatus.UPCOMING);

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(productRepository.findByIdAndStore(1L, store)).willReturn(Optional.of(product));

        // when
        ProductDetailResponse response = productService.getAdminProduct(1L, 1L);

        // then
        assertThat(response.name()).isEqualTo("관리자상품");
        assertThat(response.status()).isEqualTo(ProductStatus.UPCOMING);
    }

    @Test
    @DisplayName("getAdminProduct_존재하지_않는_관리자_STORE_ADMIN_NOT_FOUND_예외")
    void getAdminProduct_존재하지_않는_관리자_STORE_ADMIN_NOT_FOUND_예외() {
        // given
        given(storeAdminRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.getAdminProduct(999L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(StoreErrorCode.STORE_ADMIN_NOT_FOUND));
    }

    @Test
    @DisplayName("getAdminProduct_타매장_상품_PRODUCT_NOT_FOUND_예외")
    void getAdminProduct_타매장_상품_PRODUCT_NOT_FOUND_예외() {
        // given
        Store store = createStore("테스트매장");
        StoreAdmin storeAdmin = createStoreAdmin(store);

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(productRepository.findByIdAndStore(999L, store)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.getAdminProduct(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("getProducts_storeId_없음_가입_매장_전체_상품_목록_반환")
    void getProducts_storeId_없음_가입_매장_전체_상품_목록_반환() {
        // given
        User user = createUser();
        Store store1 = createStore("매장1");
        Store store2 = createStore("매장2");
        Product product1 = createProduct(store1, "상품1", ProductStatus.ACTIVE);
        Product product2 = createProduct(store2, "상품2", ProductStatus.ACTIVE);
        Pageable pageable = PageRequest.of(0, 20);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(userStoreRepository.findAllByUser(user))
                .willReturn(List.of(UserStore.create(user, store1, false), UserStore.create(user, store2, true)));
        given(productRepository.findAllByStoreInAndStatus(List.of(store1, store2), ProductStatus.ACTIVE, pageable))
                .willReturn(new PageImpl<>(List.of(product1, product2), pageable, 2));

        // when
        Page<ProductSummaryResponse> result = productService.getProducts(1L, null, pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).extracting(ProductSummaryResponse::name)
                .containsExactly("상품1", "상품2");
    }

    @Test
    @DisplayName("getProducts_storeId_지정_상품_목록_반환")
    void getProducts_storeId_지정_상품_목록_반환() {
        // given
        User user = createUser();
        Store store = createStore("테스트매장");
        Product product = createProduct(store, "상품1", ProductStatus.ACTIVE);
        Pageable pageable = PageRequest.of(0, 20);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store));
        given(userStoreRepository.existsByUserAndStore(user, store)).willReturn(true);
        given(productRepository.findAllByStoreAndStatus(store, ProductStatus.ACTIVE, pageable))
                .willReturn(new PageImpl<>(List.of(product), pageable, 1));

        // when
        Page<ProductSummaryResponse> result = productService.getProducts(1L, 1L, pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting(ProductSummaryResponse::name)
                .containsExactly("상품1");
    }

    @Test
    @DisplayName("getProducts_가입_매장_없음_empty_page_반환")
    void getProducts_가입_매장_없음_empty_page_반환() {
        // given
        User user = createUser();
        Pageable pageable = PageRequest.of(0, 20);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(userStoreRepository.findAllByUser(user)).willReturn(List.of());

        // when
        Page<ProductSummaryResponse> result = productService.getProducts(1L, null, pageable);

        // then
        assertThat(result).isEmpty();
        assertThat(result.getPageable()).isEqualTo(pageable);
    }

    @Test
    @DisplayName("getProducts_존재하지_않는_회원_USER_NOT_FOUND_예외")
    void getProducts_존재하지_않는_회원_USER_NOT_FOUND_예외() {
        // given
        Pageable pageable = PageRequest.of(0, 20);
        given(userRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.getProducts(999L, null, pageable))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));
    }

    @Test
    @DisplayName("getProducts_storeId_지정_존재하지_않는_매장_STORE_NOT_FOUND_예외")
    void getProducts_storeId_지정_존재하지_않는_매장_STORE_NOT_FOUND_예외() {
        // given
        User user = createUser();
        Pageable pageable = PageRequest.of(0, 20);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(storeRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.getProducts(1L, 999L, pageable))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(StoreErrorCode.STORE_NOT_FOUND));
    }

    @Test
    @DisplayName("getProducts_storeId_지정_미가입_매장_STORE_NOT_FOUND_예외")
    void getProducts_storeId_지정_미가입_매장_STORE_NOT_FOUND_예외() {
        // given
        User user = createUser();
        Store store = createStore("테스트매장");
        Pageable pageable = PageRequest.of(0, 20);

        given(userRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(user));
        given(storeRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(store));
        given(userStoreRepository.existsByUserAndStore(user, store)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> productService.getProducts(1L, 1L, pageable))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(StoreErrorCode.STORE_NOT_FOUND));
    }

    @Test
    @DisplayName("getAdminProducts_관리자_상품_목록_반환")
    void getAdminProducts_관리자_상품_목록_반환() {
        // given
        Store store = createStore("테스트매장");
        StoreAdmin storeAdmin = createStoreAdmin(store);
        Product product = createProduct(store, "상품1", ProductStatus.CLOSED);
        Pageable pageable = PageRequest.of(0, 20);

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(productRepository.findAllByStore(store, pageable))
                .willReturn(new PageImpl<>(List.of(product), pageable, 1));

        // when
        Page<ProductSummaryResponse> result = productService.getAdminProducts(1L, pageable);

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting(ProductSummaryResponse::name)
                .containsExactly("상품1");
    }

    @Test
    @DisplayName("getAdminProducts_존재하지_않는_관리자_STORE_ADMIN_NOT_FOUND_예외")
    void getAdminProducts_존재하지_않는_관리자_STORE_ADMIN_NOT_FOUND_예외() {
        // given
        Pageable pageable = PageRequest.of(0, 20);
        given(storeAdminRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.getAdminProducts(999L, pageable))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(StoreErrorCode.STORE_ADMIN_NOT_FOUND));
    }

    @Test
    @DisplayName("createProduct_관리자_상품_등록_UPCOMING_상태로_저장")
    void createProduct_관리자_상품_등록_UPCOMING_상태로_저장() {
        // given
        Store store = createStore("테스트매장");
        StoreAdmin storeAdmin = createStoreAdmin(store);
        CreateProductRequest request = createProductRequest();

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(productRepository.save(any(Product.class))).willAnswer(inv -> inv.getArgument(0));

        // when
        ProductDetailResponse response = productService.createProduct(1L, request);

        // then
        assertThat(response.name()).isEqualTo("신규상품");
        assertThat(response.status()).isEqualTo(ProductStatus.UPCOMING);
        assertThat(response.remainingStock()).isEqualTo(100);
        verify(productRepository).save(any(Product.class));
    }

    @Test
    @DisplayName("createProduct_존재하지_않는_관리자_STORE_ADMIN_NOT_FOUND_예외")
    void createProduct_존재하지_않는_관리자_STORE_ADMIN_NOT_FOUND_예외() {
        // given
        CreateProductRequest request = createProductRequest();
        given(storeAdminRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.createProduct(999L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(StoreErrorCode.STORE_ADMIN_NOT_FOUND));
    }

    @Test
    @DisplayName("updateProduct_관리자_상품_수정")
    void updateProduct_관리자_상품_수정() {
        // given
        Store store = createStore("테스트매장");
        StoreAdmin storeAdmin = createStoreAdmin(store);
        Product product = createProduct(store, "기존상품", ProductStatus.UPCOMING);
        UpdateProductRequest request = new UpdateProductRequest(
                "수정상품", "수정 설명", 20_000L, 150, startAt.plusDays(1), endAt.plusDays(1)
        );

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(productRepository.findByIdAndStore(1L, store)).willReturn(Optional.of(product));

        // when
        ProductDetailResponse response = productService.updateProduct(1L, 1L, request);

        // then
        assertThat(response.name()).isEqualTo("수정상품");
        assertThat(response.description()).isEqualTo("수정 설명");
        assertThat(response.price()).isEqualTo(20_000L);
        assertThat(response.totalStock()).isEqualTo(150);
        assertThat(product.getName()).isEqualTo("수정상품");
    }

    @Test
    @DisplayName("updateProduct_존재하지_않는_관리자_STORE_ADMIN_NOT_FOUND_예외")
    void updateProduct_존재하지_않는_관리자_STORE_ADMIN_NOT_FOUND_예외() {
        // given
        UpdateProductRequest request = new UpdateProductRequest("수정상품", null, null, null, null, null);
        given(storeAdminRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.updateProduct(999L, 1L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(StoreErrorCode.STORE_ADMIN_NOT_FOUND));
    }

    @Test
    @DisplayName("updateProduct_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외")
    void updateProduct_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외() {
        // given
        Store store = createStore("테스트매장");
        StoreAdmin storeAdmin = createStoreAdmin(store);
        UpdateProductRequest request = new UpdateProductRequest("수정상품", null, null, null, null, null);

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(productRepository.findByIdAndStore(999L, store)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.updateProduct(1L, 999L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("closeProduct_관리자_상품_종료")
    void closeProduct_관리자_상품_종료() {
        // given
        Store store = createStore("테스트매장");
        StoreAdmin storeAdmin = createStoreAdmin(store);
        Product product = createProduct(store, "상품1", ProductStatus.ACTIVE);

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(productRepository.findByIdAndStore(1L, store)).willReturn(Optional.of(product));

        // when
        productService.closeProduct(1L, 1L);

        // then
        assertThat(product.getStatus()).isEqualTo(ProductStatus.CLOSED);
    }

    @Test
    @DisplayName("closeProduct_존재하지_않는_관리자_STORE_ADMIN_NOT_FOUND_예외")
    void closeProduct_존재하지_않는_관리자_STORE_ADMIN_NOT_FOUND_예외() {
        // given
        given(storeAdminRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.closeProduct(999L, 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(StoreErrorCode.STORE_ADMIN_NOT_FOUND));
    }

    @Test
    @DisplayName("closeProduct_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외")
    void closeProduct_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외() {
        // given
        Store store = createStore("테스트매장");
        StoreAdmin storeAdmin = createStoreAdmin(store);

        given(storeAdminRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(storeAdmin));
        given(productRepository.findByIdAndStore(999L, store)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.closeProduct(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Test
    @DisplayName("decreaseStock_상품_재고_차감")
    void decreaseStock_상품_재고_차감() {
        // given
        Store store = createStore("테스트매장");
        Product product = createProduct(store, "상품1", ProductStatus.ACTIVE);

        given(productRepository.findByIdWithLock(1L)).willReturn(Optional.of(product));

        // when
        productService.decreaseStock(1L, 3);

        // then
        assertThat(product.getRemainingStock()).isEqualTo(97);
    }

    @Test
    @DisplayName("decreaseStock_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외")
    void decreaseStock_존재하지_않는_상품_PRODUCT_NOT_FOUND_예외() {
        // given
        given(productRepository.findByIdWithLock(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> productService.decreaseStock(999L, 1))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    private User createUser() {
        return User.of("홍길동", "010-1234-5678");
    }

    private Store createStore(String name) {
        return Store.create(name, "서울시 강남구", "02-1234-5678");
    }

    private StoreAdmin createStoreAdmin(Store store) {
        return StoreAdmin.of(store, "admin@gongu.com", "encodedPassword", "관리자");
    }

    private Product createProduct(Store store, String name, ProductStatus status) {
        return Product.create(store, name, "상품 설명", 10_000L, 100, status, startAt, endAt);
    }

    private CreateProductRequest createProductRequest() {
        return new CreateProductRequest("신규상품", "신규 상품 설명", 10_000L, 100, startAt, endAt);
    }
}
