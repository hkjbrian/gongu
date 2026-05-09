package com.gongu.server.domain.product.repository;

import com.gongu.server.domain.product.domain.Product;
import com.gongu.server.domain.product.domain.ProductStatus;
import com.gongu.server.domain.store.entity.Store;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // ADR-005: 비관적 락으로 상품 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdWithLock(@Param("id") Long id);

    // 관리자: 자신의 매장 상품 목록 (페이지네이션)
    @Query(value = "SELECT p FROM Product p WHERE p.store = :store",
           countQuery = "SELECT COUNT(p) FROM Product p WHERE p.store = :store")
    Page<Product> findAllByStore(@Param("store") Store store, Pageable pageable);

    // 회원: 가입한 매장들의 상품 목록 (storeId 필터 있을 때)
    @Query(value = "SELECT p FROM Product p WHERE p.store = :store AND p.status = :status",
           countQuery = "SELECT COUNT(p) FROM Product p WHERE p.store = :store AND p.status = :status")
    Page<Product> findAllByStoreAndStatus(@Param("store") Store store,
                                          @Param("status") ProductStatus status,
                                          Pageable pageable);

    // 회원: 가입한 여러 매장들의 활성 상품 목록
    @Query(value = "SELECT p FROM Product p WHERE p.store IN :stores AND p.status = :status",
           countQuery = "SELECT COUNT(p) FROM Product p WHERE p.store IN :stores AND p.status = :status")
    Page<Product> findAllByStoreInAndStatus(@Param("stores") List<Store> stores,
                                            @Param("status") ProductStatus status,
                                            Pageable pageable);
}
