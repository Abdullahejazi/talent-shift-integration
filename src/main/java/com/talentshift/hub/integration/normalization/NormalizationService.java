package com.talentshift.hub.integration.normalization;

import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.*;

@Service
public class NormalizationService {
    private static final Set<String> TRACKING = Set.of("utm_source","utm_medium","utm_campaign","utm_term","utm_content","gclid","fbclid");

    public String url(String input) {
        if (blank(input)) return null;
        try {
            URI u = new URI(input.trim());
            String scheme = Optional.ofNullable(u.getScheme()).orElse("https").toLowerCase();
            String host = Optional.ofNullable(u.getHost()).orElse("").toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            int port = u.getPort();
            if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) port = -1;
            String path = Optional.ofNullable(u.getPath()).orElse("");
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String query = normalizeQuery(u.getRawQuery());
            return new URI(scheme, null, host, port, path, query, null).toASCIIString();
        } catch (URISyntaxException e) {
            return input.trim().toLowerCase(Locale.ROOT);
        }
    }
    public String domain(String input) {
        if (blank(input)) return null;
        String value = input.trim().toLowerCase(Locale.ROOT);
        try {
            String host = value.contains("://") ? new URI(value).getHost() : value;
            if (host != null && host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (URISyntaxException e) { return value.replaceFirst("^www\\.", ""); }
    }
    public String text(String input) {
        if (blank(input)) return null;
        return Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }
    private String normalizeQuery(String raw) {
        if (blank(raw)) return null;
        return Arrays.stream(raw.split("&")).filter(p -> !TRACKING.contains(p.split("=",2)[0].toLowerCase(Locale.ROOT)))
                .sorted().reduce((a,b) -> a + "&" + b).orElse(null);
    }
    private boolean blank(String s) { return s == null || s.isBlank(); }
}
