package com.gongu.server.domain.order.entity;

import com.gongu.server.domain.user.entity.User;
import com.gongu.server.global.common.BaseEntity;
import com.gongu.server.global.exception.BusinessException;
import com.gongu.server.global.exception.errorcode.OrderErrorCode;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    private List<OrderItem> orderItems = new ArrayList<>();

    public static Order create(User user, long totalPrice) {
        if (user == null) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_DATA);
        }
        if (totalPrice <= 0) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_DATA);
        }
        Order order = new Order();
        order.user = user;
        order.totalPrice = totalPrice;
        order.status = OrderStatus.RESERVED;
        order.orderItems = new ArrayList<>();
        return order;
    }

    public void addOrderItem(OrderItem item) {
        this.orderItems.add(item);
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
