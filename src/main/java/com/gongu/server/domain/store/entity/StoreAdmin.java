package com.gongu.server.domain.store.entity;

import com.gongu.server.global.common.SoftDeleteEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Entity
@Table(name = "store_admins")
public class StoreAdmin extends SoftDeleteEntity {

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

    public static StoreAdmin of(Long storeId, String email, String encodedPassword, String name) {
        StoreAdmin storeAdmin = new StoreAdmin();
        storeAdmin.storeId = storeId;
        storeAdmin.email = email;
        storeAdmin.password = encodedPassword;
        storeAdmin.name = name;
        storeAdmin.isActive = true;
        return storeAdmin;
    }

    public void updatePassword(String encodedPassword) {
        this.password = encodedPassword;
    }

    public void deactivate() {
        if (isDeleted()) {
            return;
        }
        this.isActive = false;
        softDelete();
    }
}
