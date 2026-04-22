package com.gongu.server.domain.user.repository;

import com.gongu.server.domain.user.entity.SocialProvider;
import com.gongu.server.domain.user.entity.UserSocial;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserSocialRepository extends JpaRepository<UserSocial, Long> {

    Optional<UserSocial> findByProviderAndSocialId(SocialProvider provider, String socialId);
}
