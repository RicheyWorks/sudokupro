package com.xai.sudokupro.service.auth;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AccountServiceTest {

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private AccountService service;

    @BeforeEach
    void setUp() {
        UserRepository repo = mock(UserRepository.class);
        lenient().when(repo.findByUsername(anyString()))
            .thenAnswer(inv -> Optional.ofNullable(users.get(inv.<String>getArgument(0))));
        lenient().when(repo.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            users.put(u.getUsername(), u);
            return u;
        });
        service = new AccountService(repo, "admin");
    }

    @Test
    void registerCreatesALoadableAccountWithHashedPassword() {
        service.register("richmond", "correct-horse-battery");

        User stored = users.get("richmond");
        assertNotNull(stored.getPasswordHash());
        assertNotEquals("correct-horse-battery", stored.getPasswordHash(), "never store plaintext");
        assertTrue(service.encoder().matches("correct-horse-battery", stored.getPasswordHash()));

        UserDetails details = service.loadUserByUsername("richmond");
        assertTrue(details.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_PLAYER")));
    }

    @Test
    void registrationClaimsAHashlessWalletRowButNeverARegisteredOne() {
        // Auto-provisioned wallet from the pre-accounts era: no hash
        User wallet = new User(null, "ada");
        wallet.setGems(40);
        users.put("ada", wallet);

        service.register("ada", "adas-password-1");
        assertEquals(40, users.get("ada").getGems(), "claiming keeps the wallet");

        assertThrows(IllegalStateException.class,
            () -> service.register("ada", "someone-else-entirely"),
            "a registered name cannot be re-claimed");
    }

    @Test
    void reservedAndMalformedNamesAndWeakPasswordsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.register("admin", "long-enough-pw"));
        assertThrows(IllegalArgumentException.class, () -> service.register("Admin", "long-enough-pw"),
            "admin reservation must be case-insensitive");
        assertThrows(IllegalArgumentException.class, () -> service.register("anonymous", "long-enough-pw"));
        assertThrows(IllegalArgumentException.class, () -> service.register("__daily__", "long-enough-pw"));
        assertThrows(IllegalArgumentException.class, () -> service.register("a b c", "long-enough-pw"));
        assertThrows(IllegalArgumentException.class, () -> service.register("ok_name", "short"));
    }

    @Test
    void unregisteredOrHashlessUsersCannotAuthenticate() {
        users.put("walletonly", new User(null, "walletonly")); // no hash
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("walletonly"));
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("ghost"));
    }

    @Test
    void passwordChangeVerifiesTheCurrentOne() {
        service.register("richmond", "original-password");

        assertThrows(SecurityException.class,
            () -> service.changePassword("richmond", "wrong-guess", "new-password-9"));
        service.changePassword("richmond", "original-password", "new-password-9");

        assertTrue(service.encoder().matches("new-password-9", users.get("richmond").getPasswordHash()));
        assertFalse(service.encoder().matches("original-password", users.get("richmond").getPasswordHash()));
    }
}
