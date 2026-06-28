package com.gongu.server.domain.product.entity;

import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.global.common.BaseEntity;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.ProductErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
@Entity
@Table(name = "products")
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;

    @Column(nullable = false)
    private Long price;

    @Column(name = "total_stock", nullable = false)
    private int totalStock;

    @Column(name = "remaining_stock", nullable = false)
    private int remainingStock;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    @Version
    private Long version;

    public static Product create(Store store, String name, String description, Long price,
                                int totalStock, ProductStatus status,
                                LocalDateTime startAt, LocalDateTime endAt) {
        if (price <= 0) {
            throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_DATA);
        }
        if (totalStock <= 0) {
            throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_DATA);
        }
        if (startAt.isAfter(endAt)) {
            throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_DATA);
        }
        return Product.builder()
                .store(store)
                .name(name)
                .description(description)
                .price(price)
                .totalStock(totalStock)
                .remainingStock(totalStock)
                .status(status)
                .startAt(startAt)
                .endAt(endAt)
                .build();
    }

    public void decreaseStock(int quantity) {
        if (this.status != ProductStatus.ACTIVE) {
            throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_STATUS);
        }
        if (quantity <= 0) {
            throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_DATA);
        }
        if (this.remainingStock < quantity) {
            throw new BusinessException(ProductErrorCode.INSUFFICIENT_STOCK);
        }
        this.remainingStock -= quantity;
        if (this.remainingStock == 0) {
            soldOut();
        }
    }

    public void confirmStock(int quantity) {
        if (this.remainingStock < quantity) {
            throw new BusinessException(ProductErrorCode.INSUFFICIENT_STOCK);
        }
        this.remainingStock -= quantity;
        if (this.remainingStock == 0 && this.status == ProductStatus.ACTIVE) {
            this.status = ProductStatus.SOLD_OUT;
        }
    }

    public void update(String name, String description, Long price,
                       Integer totalStock, LocalDateTime startAt, LocalDateTime endAt) {
        if (this.status != ProductStatus.UPCOMING) {
            throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_STATUS);
        }
        if (name != null) this.name = name;
        if (description != null) this.description = description;
        if (price != null) {
            if (price <= 0) throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_DATA);
            this.price = price;
        }
        if (totalStock != null) {
            if (totalStock <= 0) throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_DATA);
            this.remainingStock += (totalStock - this.totalStock);
            this.totalStock = totalStock;
        }
        if (startAt != null) this.startAt = startAt;
        if (endAt != null) this.endAt = endAt;
        if (this.startAt.isAfter(this.endAt)) {
            throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_DATA);
        }
    }

    public void close() {
        this.status = ProductStatus.CLOSED;
    }

    public void activate() {
        if (this.status != ProductStatus.UPCOMING) {
            throw new BusinessException(ProductErrorCode.CANNOT_ACTIVATE_PRODUCT);
        }
        this.status = ProductStatus.ACTIVE;
    }

    public void soldOut() {
        if (this.status != ProductStatus.ACTIVE) {
            throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_STATUS);
        }
        if (this.remainingStock != 0) {
            throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_DATA);
        }
        this.status = ProductStatus.SOLD_OUT;
    }

    public void restoreStock(int quantity) {
        if (this.remainingStock + quantity > this.totalStock) {
            throw new BusinessException(ProductErrorCode.INVALID_PRODUCT_DATA);
        }
        this.remainingStock += quantity;
        // 재고 복원 시 SOLD_OUT 상태를 해제하여 재구매 가능하게 함
        if (this.status == ProductStatus.SOLD_OUT) {
            this.status = ProductStatus.ACTIVE;
        }
    }
}
