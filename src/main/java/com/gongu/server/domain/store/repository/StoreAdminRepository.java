package com.gongu.server.domain.store.repository;

import com.gongu.server.domain.store.entity.StoreAdmin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StoreAdminRepository extends JpaRepository<StoreAdmin, Long> {

    Optional<StoreAdmin> findByEmailAndIsActiveTrueAndDeletedAtIsNull(String email);

    @Query("SELECT sa FROM StoreAdmin sa JOIN FETCH sa.store WHERE sa.id = :id AND sa.deletedAt IS NULL")
    Optional<StoreAdmin> findByIdAndDeletedAtIsNull(@Param("id") Long id);
}
