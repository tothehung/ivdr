package com.ivdr.security;

import com.ivdr.domain.auth.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Immutable representation of an authenticated IVDR user, used throughout the
 * Spring Security filter chain and stored inside the {@link org.springframework.security.core.context.SecurityContext}.
 *
 * <p>Implemented as a Java 21 {@code record} so all fields are final and the class
 * is effectively a value object — equal instances with the same field values are
 * interchangeable.
 *
 * <p>Spring Security's {@link UserDetails} contract is satisfied by the explicit
 * method implementations below; the record component accessors expose the raw
 * field values directly (e.g. {@code principal.email()}).
 *
 * @param userId         UUID of the user entity in the database
 * @param organizationId UUID of the organisation this user belongs to (used for multi-tenancy)
 * @param email          user's login e-mail address
 * @param fullName       user's display name
 * @param role           single role string (e.g. "ADMIN", "MANAGER", "USER")
 * @param passwordHash   BCrypt-hashed password; stored here so Spring Security can
 *                       authenticate during the login flow
 */
public record UserPrincipal(
        UUID   userId,
        UUID   organizationId,
        String email,
        String fullName,
        String role,
        String passwordHash
) implements UserDetails {

    // -------------------------------------------------------------------------
    // Static factory
    // -------------------------------------------------------------------------

    /**
     * Constructs a {@link UserPrincipal} from a persistent {@link User} entity.
     *
     * <p>This factory is the single point of conversion between the domain model and
     * the security model, keeping the mapping logic in one place.
     *
     * @param user a fully-populated {@link User} domain object (must not be {@code null})
     * @return corresponding {@link UserPrincipal}
     */
    public static UserPrincipal fromUser(User user) {
        return new UserPrincipal(
                user.getId(),
                user.getOrganization().getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getPasswordHash()
        );
    }

    // -------------------------------------------------------------------------
    // UserDetails — authority / role
    // -------------------------------------------------------------------------

    /**
     * Returns a singleton list containing the Spring Security authority derived
     * from this principal's role string.
     *
     * <p>The convention {@code ROLE_<ROLE>} is required by Spring Security's
     * {@code hasRole()} expressions (which strip the prefix internally).
     *
     * @return unmodifiable list with one {@link SimpleGrantedAuthority}
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    // -------------------------------------------------------------------------
    // UserDetails — credentials
    // -------------------------------------------------------------------------

    /**
     * Returns the BCrypt-hashed password.  Spring Security compares this against
     * the raw password supplied at login using the configured {@link
     * org.springframework.security.crypto.password.PasswordEncoder}.
     */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    /**
     * The username used by Spring Security is the user's e-mail address.
     * This must be unique within the system.
     */
    @Override
    public String getUsername() {
        return email;
    }

    // -------------------------------------------------------------------------
    // UserDetails — account status flags
    // -------------------------------------------------------------------------

    /**
     * @return {@code true} — account locking is managed at the service layer,
     *         not via UserDetails flags in this implementation
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * @return {@code true} — same reasoning as {@link #isAccountNonExpired()}
     */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /**
     * @return {@code true} — credential expiry is not enforced via UserDetails flags
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * @return {@code true} — account enable/disable is managed at the service layer
     */
    @Override
    public boolean isEnabled() {
        return true;
    }
}
