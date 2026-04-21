package com.gongu.server.domain.store.repository;

import com.gongu.server.domain.store.entity.StoreAdmin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoreAdminRepository extends JpaRepository<StoreAdmin, Long> {

    Optional<StoreAdmin> findByEmailAndIsActiveTrue(String email);
}
