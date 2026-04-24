package com.gongu.server.domain.store.repository;

import com.gongu.server.domain.store.entity.Store;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    Optional<Store> findByIdAndDeletedAtIsNull(Long id);

    Page<Store> findAllByIsActiveTrueAndDeletedAtIsNull(Pageable pageable);
}
