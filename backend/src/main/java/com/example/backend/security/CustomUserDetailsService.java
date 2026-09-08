package com.example.backend.security;

import com.example.backend.entity.AppUser;
import com.example.backend.repository.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads users for Spring Security.
 *
 * <p>Two lookups are offered on purpose:</p>
 * <ul>
 *   <li>{@link #loadUserByUsername(String)} looks up by email. Spring Security
 *       calls this during <em>login</em>, when an email is all we have.</li>
 *   <li>{@link #loadUserById(Long)} looks up by database id. The JWT filter calls
 *       this on every subsequent request, because the token carries the id.</li>
 * </ul>
 *
 * <p>Both now throw {@link UsernameNotFoundException}. Previously a missing user
 * raised a generic "not found" exception, and because the JWT filter is a servlet
 * filter that runs before Spring MVC, the controller advice never saw it - the
 * caller got a raw HTTP 500 instead of a clean 401.</p>
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;

    public CustomUserDetailsService(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return userRepository.findByEmailIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("No account exists for that email address."));
    }

    /**
     * Looks a user up by their immutable database id.
     *
     * @throws UsernameNotFoundException if the account has been deleted since the
     *         token was issued.
     */
    public AppUser loadUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "The account this session belongs to no longer exists."));
    }
}
