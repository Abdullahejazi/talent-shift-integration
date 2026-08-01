package com.talentshift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

record CandidateProfile(UUID userId, String fullName, String email, String phone, String location,
        String headline, String summary, List<String> skills, String cvFilename, Instant updatedAt) {
    CandidateProfile { skills = skills == null ? List.of() : List.copyOf(skills); }
    static CandidateProfile empty(UUID userId, String email, String displayName) {
        return new CandidateProfile(userId, displayName, email, null, null, null, null, List.of(), null, null);
    }
}

record ProfileUpdate(@Size(max=160) String fullName, @Email @Size(max=320) String email,
        @Size(max=80) String phone, @Size(max=180) String location,
        @Size(max=240) String headline, @Size(max=4000) String summary,
        List<@Size(max=80) String> skills) {}

@Repository
class ProfileRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    ProfileRepository(JdbcClient jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    Optional<CandidateProfile> find(UUID userId) {
        return jdbc.sql("""
                SELECT user_id, full_name, email, phone, location, headline, summary,
                       skills_json, cv_filename, updated_at
                FROM candidate_profiles WHERE user_id=:userId
                """).param("userId", userId)
                .query((rs, rowNum) -> new CandidateProfile(rs.getObject("user_id", UUID.class),
                        rs.getString("full_name"), rs.getString("email"), rs.getString("phone"),
                        rs.getString("location"), rs.getString("headline"), rs.getString("summary"),
                        parseSkills(rs.getString("skills_json")), rs.getString("cv_filename"),
                        Optional.ofNullable(rs.getObject("updated_at", java.time.OffsetDateTime.class)).map(java.time.OffsetDateTime::toInstant).orElse(null))).optional();
    }

    CandidateProfile save(CandidateProfile profile) {
        jdbc.sql("""
                INSERT INTO candidate_profiles(user_id, full_name, email, phone, location, headline,
                    summary, skills_json, cv_filename, updated_at)
                VALUES (:userId,:fullName,:email,:phone,:location,:headline,:summary,:skillsJson,:cvFilename,now())
                ON CONFLICT (user_id) DO UPDATE SET
                    full_name=EXCLUDED.full_name, email=EXCLUDED.email, phone=EXCLUDED.phone,
                    location=EXCLUDED.location, headline=EXCLUDED.headline, summary=EXCLUDED.summary,
                    skills_json=EXCLUDED.skills_json,
                    cv_filename=COALESCE(EXCLUDED.cv_filename,candidate_profiles.cv_filename), updated_at=now()
                """).param("userId", profile.userId()).param("fullName", profile.fullName())
                .param("email", profile.email()).param("phone", profile.phone()).param("location", profile.location())
                .param("headline", profile.headline()).param("summary", profile.summary())
                .param("skillsJson", writeSkills(profile.skills())).param("cvFilename", profile.cvFilename()).update();
        return find(profile.userId()).orElseThrow();
    }

    void recordUpload(UUID userId, String originalFilename, String storedFilename, String contentType, long size) {
        jdbc.sql("INSERT INTO cv_uploads(user_id,original_filename,stored_filename,content_type,size_bytes) VALUES (:userId,:original,:stored,:contentType,:size)")
                .param("userId", userId).param("original", originalFilename).param("stored", storedFilename)
                .param("contentType", contentType).param("size", size).update();
    }

    private List<String> parseSkills(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { return List.of(); }
    }
    private String writeSkills(List<String> skills) {
        try { return mapper.writeValueAsString(skills == null ? List.of() : skills); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Could not serialize skills", exception); }
    }
}

@Service
class CvProfileAgent {
    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?:\\+?\\d[\\d ()-]{7,}\\d)");
    private static final List<String> SKILLS = List.of("java","spring boot","spring","python","javascript",
            "typescript","react","angular","vue","html","css","sql","postgresql","mysql","mongodb",
            "redis","docker","kubernetes","aws","azure","gcp","git","github","rest api","graphql",
            "microservices","linux","figma","ui/ux","product management","data analysis","excel",
            "power bi","tableau","machine learning","artificial intelligence","cybersecurity","networking",
            "devops","customer service","sales","marketing","project management","communication","leadership");

    CandidateProfile analyze(UUID userId, AuthenticatedUser user, String filename, String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        Set<String> skills = new LinkedHashSet<>();
        for (String skill : SKILLS) if (lower.contains(skill)) skills.add(skill);
        String email = find(EMAIL, normalized).orElse(user.email());
        String phone = find(PHONE, normalized).orElse(null);
        String headline = firstMeaningfulLine(text).orElse("Candidate profile");
        if (headline.length() > 240) headline = headline.substring(0, 240);
        String summary = normalized.isBlank() ? "CV uploaded. Add a summary to improve job matching." : normalized;
        if (summary.length() > 1200) summary = summary.substring(0, 1200) + "…";
        return new CandidateProfile(userId, user.displayName(), email, phone, null, headline, summary,
                List.copyOf(skills), filename, Instant.now());
    }

    private static Optional<String> find(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Optional.of(matcher.group().trim()) : Optional.empty();
    }
    private static Optional<String> firstMeaningfulLine(String text) {
        if (text == null) return Optional.empty();
        for (String line : text.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.length() >= 3 && trimmed.length() <= 240 && !EMAIL.matcher(trimmed).find()) return Optional.of(trimmed);
        }
        return Optional.empty();
    }
}

@Service
class CvStorageService {
    private static final Set<String> ALLOWED = Set.of("pdf","doc","docx","txt","rtf");
    private final Path uploadDirectory;
    private final long maxBytes;
    CvStorageService(@Value("${app.storage.upload-dir:uploads}") String uploadDirectory,
            @Value("${app.storage.max-bytes:5242880}") long maxBytes) {
        this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    StoredCv store(MultipartFile file, UUID userId) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Choose a CV file to upload");
        if (file.getSize() > maxBytes) throw new IllegalArgumentException("The CV exceeds the configured upload limit");
        String original = sanitizeFilename(file.getOriginalFilename());
        String extension = extension(original);
        if (!ALLOWED.contains(extension)) throw new IllegalArgumentException("Allowed CV formats are PDF, DOC, DOCX, RTF, and TXT");
        String stored = userId + "-" + UUID.randomUUID() + "." + extension;
        try {
            Files.createDirectories(uploadDirectory);
            Path destination = uploadDirectory.resolve(stored).normalize();
            if (!destination.startsWith(uploadDirectory)) throw new IllegalArgumentException("Invalid file path");
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return new StoredCv(original, stored, file.getContentType(), file.getSize(), destination);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not store the CV", exception);
        }
    }

    private static String sanitizeFilename(String filename) {
        String value = filename == null ? "cv.pdf" : Path.of(filename).getFileName().toString();
        return value.replaceAll("[^A-Za-z0-9._ -]", "_");
    }
    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    record StoredCv(String originalFilename, String storedFilename, String contentType, long size, Path path) {}
}

@Service
class CvTextExtractor {
    String extract(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(250_000);
            parser.parse(input, handler, new Metadata(), new ParseContext());
            return handler.toString();
        } catch (Exception exception) {
            throw new IllegalArgumentException("The CV could not be read. Try a standard PDF or DOCX file.");
        }
    }
}

@Service
class ProfileService {
    private final ProfileRepository profiles;
    private final CvStorageService storage;
    private final CvTextExtractor extractor;
    private final CvProfileAgent agent;
    ProfileService(ProfileRepository profiles, CvStorageService storage, CvTextExtractor extractor, CvProfileAgent agent) {
        this.profiles=profiles; this.storage=storage; this.extractor=extractor; this.agent=agent;
    }

    CandidateProfile get(AuthenticatedUser user) {
        return profiles.find(user.id()).orElseGet(() -> CandidateProfile.empty(user.id(), user.email(), user.displayName()));
    }

    CandidateProfile update(AuthenticatedUser user, ProfileUpdate update) {
        CandidateProfile current = get(user);
        List<String> skills = update.skills() == null ? current.skills() : update.skills().stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().limit(40).toList();
        return profiles.save(new CandidateProfile(user.id(), fallback(update.fullName(), current.fullName()),
                fallback(update.email(), current.email()), fallback(update.phone(), current.phone()),
                fallback(update.location(), current.location()), fallback(update.headline(), current.headline()),
                fallback(update.summary(), current.summary()), skills, current.cvFilename(), Instant.now()));
    }

    @Transactional
    CandidateProfile upload(AuthenticatedUser user, MultipartFile file) {
        CvStorageService.StoredCv stored = storage.store(file, user.id());
        CandidateProfile analyzed = agent.analyze(user.id(), user, stored.originalFilename(), extractor.extract(stored.path()));
        profiles.recordUpload(user.id(), stored.originalFilename(), stored.storedFilename(), stored.contentType(), stored.size());
        return profiles.save(analyzed);
    }

    private static String fallback(String proposed, String current) { return proposed == null ? current : proposed.trim(); }
}

@RestController
@RequestMapping("/api/profile")
class ProfileController {
    private final ProfileService profiles;
    ProfileController(ProfileService profiles) { this.profiles = profiles; }
    @GetMapping CandidateProfile get(Authentication authentication) { return profiles.get(user(authentication)); }
    @PutMapping CandidateProfile update(Authentication authentication, @Valid @RequestBody ProfileUpdate update) {
        return profiles.update(user(authentication), update);
    }
    @org.springframework.web.bind.annotation.PostMapping(path="/cv", consumes="multipart/form-data")
    CandidateProfile upload(Authentication authentication, @RequestPart("file") MultipartFile file) {
        return profiles.upload(user(authentication), file);
    }
    private static AuthenticatedUser user(Authentication authentication) {
        return (AuthenticatedUser) authentication.getPrincipal();
    }
}
