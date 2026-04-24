package com.gongu.server.domain.store.entity;

import com.gongu.server.global.common.SoftDeleteEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "stores")
public class Store extends SoftDeleteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false)
    private boolean isActive;

    @Builder(access = AccessLevel.PRIVATE)
    private Store(String name, String address, String phone, boolean isActive) {
        this.name = name;
        this.address = address;
        this.phone = phone;
        this.isActive = isActive;
    }

    public static Store create(String name, String address, String phone) {
        return Store.builder()
                .name(name)
                .address(address)
                .phone(phone)
                .isActive(true)
                .build();
    }

    public void deactivate() {
        if (isDeleted()) {
            return;
        }
        this.isActive = false;
        softDelete();
    }
}
