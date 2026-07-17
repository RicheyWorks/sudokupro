package com.xai.sudokupro.service.auth;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Real player accounts. Registration writes a BCrypt hash onto the existing
 * {@code users} rows (the same rows that hold wallets, ratings, and friends),
 * and {@link #loadUserByUsername} exposes them to Spring Security as a
 * DB-backed {@link UserDetailsService} with ROLE_PLAYER.
 *
 * <p>Usernames auto-provisioned as wallets before registration existed have a
 * NULL hash; the first registration for such a name claims it. The env-provided
 * admin account is separate (in-memory, ROLE_ADMIN) and reserved.
 */
@Service
public class AccountService implements UserDetailsService {

    private static final Logger logger = LoggerFactory.getLogger(AccountService.class);
    private static final Pattern USERNAME = Pattern.compile("^[a-zA-Z0-9_-]{3,20}$");
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository userRepository;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final String adminUsername;

    public AccountService(UserRepository userRepository,
                          @Value("${spring.security.user.name:admin}") String adminUsername) {
        this.userRepository = userRepository;
        this.adminUsername = adminUsername;
    }

    /**
     * Registers a new player (or claims a hash-less wallet row of the same name).
     *
     * @throws IllegalArgumentException invalid username/password
     * @throws IllegalStateException    username already registered
     */
    @Transactional
    public void register(String username, String password) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("Username must be 3-20 characters: letters, digits, _ or -");
        }
        if (username.equalsIgnoreCase(adminUsername) || username.startsWith("__") || username.equals("anonymous")) {
            throw new IllegalArgumentException("That username is reserved");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        Optional<User> existing = userRepository.findByUsername(username);
        if (existing.isPresent() && existing.get().getPasswordHash() != null) {
            throw new IllegalStateException("Username already taken: " + username);
        }
        User user = existing.orElseGet(() -> new User(null, username));
        user.setPasswordHash(encoder.encode(password));
        userRepository.save(user);
        logger.info("Registered player account {}{}", username,
            existing.isPresent() ? " (claimed existing wallet row)" : "");
    }

    /** Changes the caller's password after verifying the current one. */
    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        User user = userRepository.findByUsername(username)
            .filter(u -> u.getPasswordHash() != null)
            .orElseThrow(() -> new IllegalStateException("No registered account: " + username));
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new SecurityException("Current password is incorrect");
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        user.setPasswordHash(encoder.encode(newPassword));
        userRepository.save(user);
        logger.info("Password changed for {}", username);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
            .filter(u -> u.getPasswordHash() != null)
            .orElseThrow(() -> new UsernameNotFoundException("No such player: " + username));
        return org.springframework.security.core.userdetails.User
            .withUsername(user.getUsername())
            .password(user.getPasswordHash())
            .roles("PLAYER")
            .build();
    }

    public PasswordEncoder encoder() {
        return encoder;
    }
}
