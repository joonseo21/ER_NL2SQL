package io.eranalytics.pipeline.webapi.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Loads once; invalid configuration disables only login and never logs file contents. */
@Component
@ConditionalOnProperty(name = "er.web-api.enabled", havingValue = "true")
public class LoginAccounts {
    public static final String USERNAME_PATTERN = "^[a-z0-9_-]{3,32}$";
    private static final Pattern USERNAME = Pattern.compile(USERNAME_PATTERN);
    private static final Logger LOG = LoggerFactory.getLogger(LoginAccounts.class);
    private final Map<String, Account> accounts;
    private final boolean enabled;

    public LoginAccounts(@Value("${er.web-api.accounts-file:}") String file, ObjectMapper json) {
        Map<String, Account> loaded = new HashMap<>();
        boolean valid = false;
        if (!file.isBlank()) {
            try (InputStream input = Files.newInputStream(Path.of(file))) {
                JsonNode root =
                        json.reader()
                                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readTree(input);
                if (root == null || !root.isArray()) throw new IllegalArgumentException();
                for (JsonNode node : root) {
                    String username = text(node, "username");
                    String hash = text(node, "password_hash");
                    String code = text(node, "invite_code");
                    if (!USERNAME.matcher(username).matches() || code.isBlank()) {
                        throw new IllegalArgumentException();
                    }
                    PasswordHashes.validate(hash);
                    if (loaded.putIfAbsent(username, new Account(hash, code)) != null) {
                        throw new IllegalArgumentException();
                    }
                }
                valid = true;
            } catch (Exception error) {
                loaded.clear();
                valid = false;
                LOG.error("Login disabled: account file could not be loaded or validated");
            }
        } else {
            LOG.warn("Login disabled: account file is not configured");
        }
        accounts = Map.copyOf(loaded);
        enabled = valid;
    }

    public boolean enabled() {
        return enabled;
    }

    public Account find(String username) {
        return accounts.get(username);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException();
        return value.textValue();
    }

    public record Account(String passwordHash, String inviteCode) {
        @Override
        public String toString() {
            return "Account[redacted]";
        }
    }
}
