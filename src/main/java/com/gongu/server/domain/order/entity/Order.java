package com.gongu.server.domain.order.entity;

import com.gongu.server.domain.user.entity.User;
import com.gongu.server.global.common.BaseEntity;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.OrderErrorCode;
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
@Table(name = "orders")
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_price", nullable = false)
    private Long totalPrice;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    public static Order create(User user, long totalPrice) {
        if (user == null) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_DATA);
        }
        if (totalPrice <= 0) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_DATA);
        }
        return Order.builder()
                .user(user)
                .totalPrice(totalPrice)
                .status(OrderStatus.RESERVED)
                .build();
    }

    public void cancel(String reason) {
        if (this.status != OrderStatus.RESERVED) {
            throw new BusinessException(OrderErrorCode.CANCEL_NOT_ALLOWED);
        }
        this.status = OrderStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
        this.cancelReason = reason;
    }

    public void pay() {
        if (this.status != OrderStatus.RESERVED) {
            throw new BusinessException(OrderErrorCode.PAY_NOT_ALLOWED);
        }
        this.status = OrderStatus.PAID;
    }

    public void arrive() {
        if (this.status != OrderStatus.PAID) {
            throw new BusinessException(OrderErrorCode.ARRIVE_NOT_ALLOWED);
        }
        this.status = OrderStatus.ARRIVED;
    }

    public void receive() {
        if (this.status != OrderStatus.ARRIVED) {
            throw new BusinessException(OrderErrorCode.RECEIVE_NOT_ALLOWED);
        }
        this.status = OrderStatus.RECEIVED;
    }
}
