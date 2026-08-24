package com.hodi.security.principal;

import com.hodi.modules.users.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads a principal by username or email — login accepts either, which is what the form offers.
 *
 * <p>One namespace for all four populations: platform staff, seller staff, lender staff and buyers all live
 * in {@code users} with unique username and email, so there is exactly one login pipeline and one session
 * model rather than a separate one for the marketplace. Which surface somebody may then reach is decided by
 * their user type and permissions, not by which form they signed in through.
 *
 * <p>Loads the <em>default</em> profile, because this path has no token to name one: it is used where Spring
 * Security wants a UserDetails for an identifier and nothing more is known yet.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository users;
    private final PrincipalFactory principals;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        String id = identifier == null ? "" : identifier.trim();
        return users.findByUsernameIgnoreCaseOrEmail(id, id.toLowerCase())
                .map(principals::buildDefault)
                // Deliberately generic: the message must not reveal whether an account exists.
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
}
