package com.hodi.modules.auth;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * A password this user has previously held, kept as its hash so reuse can be refused.
 *
 * <p>Depth is configuration ({@code auth.password.history.count}), not a constant here — the rows accumulate
 * and the policy decides how far back to look, so tightening the policy applies to history already recorded
 * rather than only to what is written after the change.
 */
@Entity
@Table(name = "password_history")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PasswordHistory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false) private Long userId;

    @Column(nullable = false, length = 128) private String password;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
