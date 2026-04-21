package com.gongu.server.domain.store.entity;

import com.gongu.server.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Entity
@Table(name = "store_admin")
public class StoreAdmin extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "store_id")
    private Long storeId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean isActive;

    @Column(nullable = false)
    private LocalDateTime deletedAt;

    public static StoreAdmin of(Long storeId, String email, String encodedPassword, String name) {
        StoreAdmin storeAdmin = new StoreAdmin();
        storeAdmin.storeId = storeId;
        storeAdmin.email = email;
        storeAdmin.password = encodedPassword;
        storeAdmin.name = name;
        storeAdmin.isActive = true;
        storeAdmin.deletedAt = LocalDateTime.of(9999, 12, 31, 0, 0, 0);
        return storeAdmin;
    }

    public void updatePassword(String encodedPassword) {
        this.password = encodedPassword;
    }
}
