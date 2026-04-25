package com.gongu.server.domain.store.entity;

import com.gongu.server.domain.user.entity.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "member_stores",
        uniqueConstraints = @UniqueConstraint(
                name = "UQ_MEMBER_STORES_USER_STORE",
                columnNames = {"user_id", "store_id"}
        )
)
public class MemberStore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "is_preferred", nullable = false)
    private boolean isPreferred;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private MemberStore(Member member, Store store, boolean isPreferred) {
        this.member = member;
        this.store = store;
        this.isPreferred = isPreferred;
    }

    public static MemberStore create(Member member, Store store, boolean isPreferred) {
        return new MemberStore(member, store, isPreferred);
    }

    public void markAsPreferred() {
        this.isPreferred = true;
    }

    public void unmarkAsPreferred() {
        this.isPreferred = false;
    }
}
