package com.ivdr.domain.auth.repository;

import com.ivdr.domain.auth.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Organization} (tenant) entities.
 *
 * <p>Organisation records are global (not tenant-scoped) by design —
 * they are the root of the multi-tenancy hierarchy.</p>
 */
@Repository
public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    /**
     * Looks up an organisation by its URL-safe slug.
     *
     * <p>Slugs are unique across the platform and are typically used to resolve
     * tenants from sub-domain or path-based routing.</p>
     *
     * @param slug the URL-safe identifier (e.g. "acme-corp")
     * @return an {@link Optional} containing the organisation, or empty if not found
     */
    Optional<Organization> findBySlug(String slug);

    /**
     * Checks whether a slug is already in use.
     *
     * <p>Call this before persisting a new organisation to provide a meaningful
     * conflict error rather than relying on a database constraint violation.</p>
     *
     * @param slug the candidate slug to check
     * @return {@code true} if the slug is already taken
     */
    boolean existsBySlug(String slug);
}
