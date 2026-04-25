package com.gongu.server.domain.store.repository;

import com.gongu.server.domain.store.entity.MemberStore;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.user.entity.Member;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberStoreRepository extends JpaRepository<MemberStore, Long> {

    boolean existsByMemberAndStore(Member member, Store store);

    Optional<MemberStore> findByMemberAndStore(Member member, Store store);

    Optional<MemberStore> findByMemberAndIsPreferredTrue(Member member);

    Page<MemberStore> findAllByStore(Store store, Pageable pageable);
}
