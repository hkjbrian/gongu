package com.gongu.server.domain.store.repository;

import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.store.entity.UserStore;
import com.gongu.server.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserStoreRepository extends JpaRepository<UserStore, Long> {

    boolean existsByUserAndStore(User user, Store store);

    @Query("SELECT ms FROM UserStore ms JOIN FETCH ms.store WHERE ms.user = :user")
    List<UserStore> findAllByUser(@Param("user") User user);

    Optional<UserStore> findByUserAndStore(User user, Store store);

    Optional<UserStore> findByUserAndIsPreferredTrue(User user);

    @Query(value = """
            SELECT ms FROM UserStore ms
            JOIN FETCH ms.user m
            WHERE ms.store = :store
              AND (:nameFilter IS NULL OR m.name LIKE %:nameFilter%)
            """,
           countQuery = """
            SELECT COUNT(ms) FROM UserStore ms
            JOIN ms.user m
            WHERE ms.store = :store
              AND (:nameFilter IS NULL OR m.name LIKE %:nameFilter%)
            """)
    Page<UserStore> findAllByStoreWithUser(
            @Param("store") Store store,
            @Param("nameFilter") String nameFilter,
            Pageable pageable);
}
