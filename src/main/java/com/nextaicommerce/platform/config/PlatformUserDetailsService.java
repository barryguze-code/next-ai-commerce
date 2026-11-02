package com.nextaicommerce.platform.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformUserDetailsService implements UserDetailsService {
    private record LoginUser(UUID id, String email, String passwordHash, String status, boolean superAdmin) {}

    private final JdbcTemplate jdbc;
    PlatformUserDetailsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String email = username.strip().toLowerCase(Locale.ROOT);
        List<LoginUser> users = jdbc.query("""
            SELECT u.id, u.email, u.password_hash, u.status,
                   EXISTS (SELECT 1 FROM platform_administrators p WHERE p.user_id = u.id AND p.active) AS super_admin
            FROM app_users u WHERE lower(u.email) = ?
            """, (rs, row) -> new LoginUser(rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getString("password_hash"), rs.getString("status"), rs.getBoolean("super_admin")), email);

        if (users.isEmpty()) {
            throw new UsernameNotFoundException("Invalid email or password");
        }

        LoginUser login = users.getFirst();
        if (!"ACTIVE".equals(login.status()) || login.passwordHash() == null) {
            throw new UsernameNotFoundException("Account is not activated");
        }
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        if (login.superAdmin()) authorities.add(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
        for (UUID tenantId : jdbc.query("SELECT id FROM tenants", (rs, row) -> rs.getObject(1, UUID.class))) {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenantId.toString());
            jdbc.queryForList("SELECT role FROM effective_account_memberships WHERE tenant_id = ? AND user_id = ?",
                String.class, tenantId, login.id()).forEach(role ->
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
        }
        return User.withUsername(login.email()).password(login.passwordHash())
            .authorities(authorities).disabled(false).build();
    }
}
