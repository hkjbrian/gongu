package com.gongu.server.domain.user.entity;

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
@Table(name = "users")
public class User extends SoftDeleteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = true)
    private String email;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private boolean isActive;

    public static User of(String name, String phone) {
        User user = new User();
        user.name = name;
        user.phone = phone;
        user.isActive = true;
        return user;
    }

    public void updateProfile(String name, String phone) {
        this.name = name;
        this.phone = phone;
    }

    public void deactivate() {
        if (isDeleted()) {
            return;
        }
        this.isActive = false;
        softDelete();
    }
}
