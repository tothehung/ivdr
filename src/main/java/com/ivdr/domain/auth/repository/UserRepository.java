package com.ivdr.domain.auth.repository;

import com.ivdr.domain.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link User} entities.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Looks up a user by e-mail address across <em>all</em> organisations.
     * Returns only one result (used for single-org lookups).
     */
    Optional<User> findByEmail(String email);

    /**
     * Returns ALL users with the given email across all organizations.
     * Used by the multi-org login flow to present an org selector.
     */
    List<User> findAllByEmail(String email);

    /**
     * Looks up a user by e-mail address <em>within a specific organisation</em>.
     *
     * <p>This is the preferred method for authentication flows because it
     * scopes the lookup to the correct tenant.</p>
     *
     * @param email          the e-mail address to search for
     * @param organizationId the tenant's organisation UUID
     * @return an {@link Optional} containing the user, or empty if not found
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.email = :email
              AND u.organization.id = :organizationId
            """)
    Optional<User> findByEmailAndOrganizationId(
            @Param("email") String email,
            @Param("organizationId") UUID organizationId);

    /**
     * Checks whether a user with the given e-mail already exists in a specific
     * organisation.  Useful for duplicate-registration guards during sign-up.
     *
     * @param email          the e-mail address to check
     * @param organizationId the tenant's organisation UUID
     * @return {@code true} if a matching user record exists
     */
    boolean existsByEmailAndOrganizationId(String email, UUID organizationId);
}
