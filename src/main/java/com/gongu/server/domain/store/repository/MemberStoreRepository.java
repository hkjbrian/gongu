package com.gongu.server.domain.store.repository;

import com.gongu.server.domain.store.entity.MemberStore;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.user.entity.Member;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MemberStoreRepository extends JpaRepository<MemberStore, Long> {

    boolean existsByMemberAndStore(Member member, Store store);

    List<MemberStore> findAllByMember(Member member);

    Optional<MemberStore> findByMemberAndStore(Member member, Store store);

    Optional<MemberStore> findByMemberAndIsPreferredTrue(Member member);

    @Query(value = """
            SELECT ms FROM MemberStore ms
            JOIN FETCH ms.member m
            WHERE ms.store = :store
              AND (:nameFilter IS NULL OR m.name LIKE %:nameFilter%)
            """,
           countQuery = """
            SELECT COUNT(ms) FROM MemberStore ms
            JOIN ms.member m
            WHERE ms.store = :store
              AND (:nameFilter IS NULL OR m.name LIKE %:nameFilter%)
            """)
    Page<MemberStore> findAllByStoreWithMember(
            @Param("store") Store store,
            @Param("nameFilter") String nameFilter,
            Pageable pageable);
}
