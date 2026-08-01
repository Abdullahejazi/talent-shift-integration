package com.talentshift;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
class SecurityModule {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionAuthenticationFilter sessionFilter,
            @Value("${COOKIE_SECURE:false}") boolean secureCookie)
            throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieName("XSRF-TOKEN");
        csrfRepository.setHeaderName("X-XSRF-TOKEN");
        csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(secureCookie).path("/"));
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/index.html", "/login.html", "/register.html", "/styles.css",
                                "/app.js", "/assets/**", "/favicon.ico",
                                "/actuator/health", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers("/api/auth/csrf", "/api/auth/login", "/api/auth/register").permitAll()
                        .requestMatchers("/api/admin/**", "/api/integration/**").hasRole("ADMIN")
                        .requestMatchers("/api/jobs/**", "/api/companies/**").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository))
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                                        + "connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(permissions -> permissions.policy(
                                "camera=(), microphone=(), geolocation=(), payment=()")))
                .addFilterBefore(sessionFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    ApplicationRunner seedDemoUser(UserAccountRepository users, PasswordEncoder passwordEncoder,
            @Value("${app.security.demo-email}") String email,
            @Value("${app.security.demo-password}") String password,
            @Value("${app.security.demo-user-enabled:false}") boolean enabled) {
        return args -> {
            if (enabled) users.upsertAccount(email, passwordEncoder.encode(password), "Demo Candidate", "CANDIDATE");
        };
    }

    @Bean
    ApplicationRunner seedAdministrator(UserAccountRepository users, PasswordEncoder passwordEncoder,
            @Value("${app.security.admin-email:admin@talentshift.local}") String email,
            @Value("${app.security.admin-password:}") String password,
            @Value("${app.security.admin-user-enabled:false}") boolean enabled) {
        return args -> {
            if (enabled && !password.isBlank()) {
                users.upsertSingleAdministrator(email, passwordEncoder.encode(password), "TalentShift Administrator");
            }
        };
    }
}

record UserAccount(UUID id, String email, String passwordHash, String displayName, String role) {}
record AuthenticatedUser(UUID id, String email, String displayName, String role) {}

@Repository
class UserAccountRepository {
    private final JdbcClient jdbc;
    UserAccountRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    Optional<UserAccount> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, password_hash, display_name, role FROM user_accounts WHERE email = lower(:email)")
                .param("email", email.trim())
                .query((rs, rowNum) -> new UserAccount(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("password_hash"), rs.getString("display_name"), rs.getString("role")))
                .optional();
    }

    void upsertAccount(String email, String passwordHash, String displayName, String role) {
        jdbc.sql("""
                INSERT INTO user_accounts(email, password_hash, display_name, role)
                VALUES (lower(:email), :passwordHash, :displayName, :role)
                ON CONFLICT (email) DO UPDATE SET
                    password_hash=EXCLUDED.password_hash, display_name=EXCLUDED.display_name, role=EXCLUDED.role
                """)
                .param("email", email.trim()).param("passwordHash", passwordHash).param("displayName", displayName)
                .param("role", role).update();
    }

    @Transactional
    void upsertSingleAdministrator(String email, String passwordHash, String displayName) {
        int updated = jdbc.sql("""
                UPDATE user_accounts
                SET email=lower(:email), password_hash=:passwordHash, display_name=:displayName
                WHERE role='ADMIN'
                """)
                .param("email", email.trim()).param("passwordHash", passwordHash)
                .param("displayName", displayName).update();
        if (updated == 0) upsertAccount(email, passwordHash, displayName, "ADMIN");
    }

    UUID create(String email, String passwordHash, String displayName) {
        UUID id = jdbc.sql("""
                INSERT INTO user_accounts(email, password_hash, display_name)
                VALUES (lower(:email), :passwordHash, :displayName)
                RETURNING id
                """)
                .param("email", email.trim()).param("passwordHash", passwordHash)
                .param("displayName", displayName.trim()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO candidate_profiles(user_id, full_name, email)
                VALUES (:id, :name, lower(:email))
                """).param("id", id).param("name", displayName.trim()).param("email", email.trim()).update();
        return id;
    }
}

@Component
class LoginAttemptGuard {
    private static final int MAX_FAILURES = 8;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private final ConcurrentHashMap<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    void check(String remoteAddress, String email) {
        String key = key(remoteAddress, email);
        AttemptWindow current = attempts.get(key);
        if (current == null) return;
        if (current.startedAt().plus(WINDOW).isBefore(Instant.now())) {
            attempts.remove(key, current);
            return;
        }
        if (current.failures() >= MAX_FAILURES) throw new TooManyLoginAttemptsException();
    }

    void failure(String remoteAddress, String email) {
        String key = key(remoteAddress, email);
        Instant now = Instant.now();
        attempts.compute(key, (ignored, current) -> current == null || current.startedAt().plus(WINDOW).isBefore(now)
                ? new AttemptWindow(1, now) : new AttemptWindow(current.failures() + 1, current.startedAt()));
    }

    void success(String remoteAddress, String email) {
        attempts.remove(key(remoteAddress, email));
    }

    private static String key(String remoteAddress, String email) {
        return Optional.ofNullable(remoteAddress).orElse("unknown") + "|"
                + Optional.ofNullable(email).orElse("").trim().toLowerCase(Locale.ROOT);
    }

    private record AttemptWindow(int failures, Instant startedAt) {}
}

@Repository
class AuthSessionRepository {
    private final JdbcClient jdbc;
    AuthSessionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    void create(UUID userId, String tokenHash, Instant expiresAt) {
        jdbc.sql("INSERT INTO auth_sessions(user_id, token_hash, expires_at) VALUES (:userId, :tokenHash, :expiresAt)")
                .param("userId", userId).param("tokenHash", tokenHash)
                .param("expiresAt", java.time.OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC), java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
    }

    Optional<AuthenticatedUser> findValid(String tokenHash) {
        return jdbc.sql("SELECT u.id, u.email, u.display_name, u.role FROM auth_sessions s JOIN user_accounts u ON u.id = s.user_id WHERE s.token_hash = :tokenHash AND s.expires_at > now()")
                .param("tokenHash", tokenHash)
                .query((rs, rowNum) -> new AuthenticatedUser(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"), rs.getString("role")))
                .optional();
    }

    void delete(String tokenHash) {
        jdbc.sql("DELETE FROM auth_sessions WHERE token_hash = :tokenHash").param("tokenHash", tokenHash).update();
    }
}

@Service
class AuthService {
    static final String SESSION_COOKIE = "TS_SESSION";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserAccountRepository users;
    private final AuthSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final Duration sessionDuration;

    AuthService(UserAccountRepository users, AuthSessionRepository sessions, PasswordEncoder passwordEncoder,
            @Value("${app.security.session-hours:24}") long sessionHours) {
        this.users = users;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.sessionDuration = Duration.ofHours(sessionHours);
    }

    LoginResult login(String email, String password) {
        UserAccount user = users.findByEmail(email)
                .filter(candidate -> passwordEncoder.matches(password, candidate.passwordHash()))
                .orElseThrow(InvalidCredentialsException::new);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(sessionDuration);
        sessions.create(user.id(), hash(rawToken), expiresAt);
        return new LoginResult(new AuthenticatedUser(user.id(), user.email(), user.displayName(), user.role()), rawToken, expiresAt);
    }

    @Transactional
    LoginResult register(String email, String password, String displayName) {
        if (users.findByEmail(email).isPresent()) throw new EmailAlreadyRegisteredException();
        try {
            users.create(email, passwordEncoder.encode(password), displayName);
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw new EmailAlreadyRegisteredException();
        }
        return login(email, password);
    }

    Optional<AuthenticatedUser> authenticate(String rawToken) {
        return rawToken == null || rawToken.isBlank() ? Optional.empty() : sessions.findValid(hash(rawToken));
    }

    void logout(String rawToken) {
        if (rawToken != null && !rawToken.isBlank()) sessions.delete(hash(rawToken));
    }

    static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record LoginResult(AuthenticatedUser user, String rawToken, Instant expiresAt) {}
}

@Component
class SessionAuthenticationFilter extends OncePerRequestFilter {
    private final AuthService authService;
    SessionAuthenticationFilter(AuthService authService) { this.authService = authService; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = cookieValue(request, AuthService.SESSION_COOKIE);
        authService.authenticate(token).ifPresent(user -> {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        });
        chain.doFilter(request, response);
    }

    static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
}

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AuthService authService;
    private final LoginAttemptGuard loginAttemptGuard;
    private final boolean secureCookie;
    AuthController(AuthService authService, LoginAttemptGuard loginAttemptGuard,
            @Value("${COOKIE_SECURE:false}") boolean secureCookie) {
        this.authService = authService;
        this.loginAttemptGuard = loginAttemptGuard;
        this.secureCookie = secureCookie;
    }

    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token) { return new CsrfResponse(token.getHeaderName(), token.getToken()); }

    @PostMapping("/login")
    AuthenticatedUser login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest,
            HttpServletResponse response) {
        String remoteAddress = servletRequest.getRemoteAddr();
        loginAttemptGuard.check(remoteAddress, request.email());
        AuthService.LoginResult result;
        try {
            result = authService.login(request.email(), request.password());
        } catch (InvalidCredentialsException exception) {
            loginAttemptGuard.failure(remoteAddress, request.email());
            throw exception;
        }
        loginAttemptGuard.success(remoteAddress, request.email());
        addSessionCookie(response, result);
        return result.user();
    }

    @PostMapping("/register")
    AuthenticatedUser register(@Valid @RequestBody RegisterRequest request, HttpServletResponse response) {
        AuthService.LoginResult result = authService.register(request.email(), request.password(), request.fullName());
        addSessionCookie(response, result);
        return result.user();
    }

    @GetMapping("/me")
    AuthenticatedUser me(org.springframework.security.core.Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }

    @PostMapping("/logout")
    void logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(SessionAuthenticationFilter.cookieValue(request, AuthService.SESSION_COOKIE));
        ResponseCookie cookie = ResponseCookie.from(AuthService.SESSION_COOKIE, "")
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/").maxAge(Duration.ZERO).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private void addSessionCookie(HttpServletResponse response, AuthService.LoginResult result) {
        ResponseCookie cookie = ResponseCookie.from(AuthService.SESSION_COOKIE, result.rawToken())
                .httpOnly(true).secure(secureCookie).sameSite("Lax").path("/")
                .maxAge(Duration.between(Instant.now(), result.expiresAt())).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}

record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}
record RegisterRequest(
        @NotBlank @Pattern(regexp = "CANDIDATE") String role,
        @NotBlank @Size(min = 2, max = 120) String fullName,
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 12, max = 128)
        @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$") String password) {}
record CsrfResponse(String headerName, String token) {}
class InvalidCredentialsException extends RuntimeException {}
class TooManyLoginAttemptsException extends RuntimeException {}
class EmailAlreadyRegisteredException extends RuntimeException {}

@org.springframework.web.bind.annotation.RestControllerAdvice
class ApiExceptionHandler {
    @org.springframework.web.bind.annotation.ExceptionHandler(EmailAlreadyRegisteredException.class)
    org.springframework.http.ResponseEntity<java.util.Map<String, String>> emailAlreadyRegistered() {
        return org.springframework.http.ResponseEntity.status(409)
                .body(java.util.Map.of("code", "EMAIL_ALREADY_REGISTERED",
                        "message", "An account already exists for this email"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(InvalidCredentialsException.class)
    org.springframework.http.ResponseEntity<java.util.Map<String, String>> invalidCredentials() {
        return org.springframework.http.ResponseEntity.status(401)
                .body(java.util.Map.of("code", "INVALID_CREDENTIALS", "message", "Email or password is incorrect"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    org.springframework.http.ResponseEntity<java.util.Map<String, String>> invalidInput(IllegalArgumentException exception) {
        return org.springframework.http.ResponseEntity.badRequest()
                .body(java.util.Map.of("code", "INVALID_REQUEST", "message", exception.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(TooManyLoginAttemptsException.class)
    org.springframework.http.ResponseEntity<java.util.Map<String, String>> tooManyLoginAttempts() {
        return org.springframework.http.ResponseEntity.status(429)
                .body(java.util.Map.of("code", "TOO_MANY_LOGIN_ATTEMPTS",
                        "message", "Too many sign-in attempts. Try again in 15 minutes."));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(CompanyNotFoundException.class)
    org.springframework.http.ResponseEntity<java.util.Map<String, String>> companyNotFound() {
        return org.springframework.http.ResponseEntity.status(404)
                .body(java.util.Map.of("code", "COMPANY_NOT_FOUND", "message", "The requested company does not exist"));
    }
}
