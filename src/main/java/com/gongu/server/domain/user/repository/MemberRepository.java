package com.gongu.server.domain.user.repository;

import com.gongu.server.domain.user.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByEmailAndDeletedAtIsNull(String email);

    Optional<Member> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByEmailAndDeletedAtIsNull(String email);
}
