package com.gongu.server.domain.store.repository;

import com.gongu.server.domain.store.entity.MemberStore;
import com.gongu.server.domain.store.entity.Store;
import com.gongu.server.domain.user.entity.Member;
import com.gongu.server.global.config.JpaConfig;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(JpaConfig.class)
class MemberStoreRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private MemberStoreRepository memberStoreRepository;

    @Autowired
    private StoreRepository storeRepository;

    private Member member;
    private Store store;

    @BeforeEach
    void setUp() {
        member = em.persist(Member.of("테스터", "test@test.com", "010-0000-0000"));
        store = em.persist(Store.create("테스트매장", "서울시", "02-1234-5678"));
        em.flush();
    }

    @Test
    void existsByMemberAndStore_등록된_경우_true_반환() {
        // given
        MemberStore memberStore = MemberStore.create(member, store, false);
        em.persist(memberStore);
        em.flush();
        em.clear();

        Member foundMember = em.find(Member.class, member.getId());
        Store foundStore = em.find(Store.class, store.getId());

        // when
        boolean result = memberStoreRepository.existsByMemberAndStore(foundMember, foundStore);

        // then
        assertThat(result).isTrue();
    }

    @Test
    void existsByMemberAndStore_미등록_경우_false_반환() {
        // when
        boolean result = memberStoreRepository.existsByMemberAndStore(member, store);

        // then
        assertThat(result).isFalse();
    }

    @Test
    void findByMemberAndIsPreferredTrue_선호_매장_있을_때_반환() {
        // given
        MemberStore memberStore = MemberStore.create(member, store, true);
        em.persist(memberStore);
        em.flush();
        em.clear();

        Member foundMember = em.find(Member.class, member.getId());

        // when
        Optional<MemberStore> result = memberStoreRepository.findByMemberAndIsPreferredTrue(foundMember);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().isPreferred()).isTrue();
        assertThat(result.get().getStore().getId()).isEqualTo(store.getId());
    }

    @Test
    void findByMemberAndIsPreferredTrue_선호_매장_없을_때_empty_반환() {
        // given
        MemberStore memberStore = MemberStore.create(member, store, false);
        em.persist(memberStore);
        em.flush();
        em.clear();

        Member foundMember = em.find(Member.class, member.getId());

        // when
        Optional<MemberStore> result = memberStoreRepository.findByMemberAndIsPreferredTrue(foundMember);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void 동일_회원_동일_매장_중복_저장시_예외_발생() {
        // given
        MemberStore first = MemberStore.create(member, store, false);
        em.persist(first);
        em.flush();

        MemberStore duplicate = MemberStore.create(member, store, false);

        // when & then
        assertThatThrownBy(() -> {
            em.persist(duplicate);
            em.flush();
        }).isInstanceOf(PersistenceException.class);
    }
}
