package io.eranalytics.pipeline.webapi;

import static org.assertj.core.api.Assertions.*;

import io.eranalytics.pipeline.webapi.service.PasswordHashes;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordHashesTest {
    @Test
    void generatesSaltedHashesAndVerifiesPasswords() {
        char[] password = "synthetic password".toCharArray();
        String hash = PasswordHashes.create(password);
        assertThat(hash).startsWith("pbkdf2_sha256$600000$");
        assertThat(Base64.getDecoder().decode(hash.split("\\$")[2])).hasSize(16);
        assertThat(PasswordHashes.matches(password, hash)).isTrue();
        assertThat(PasswordHashes.matches("wrong".toCharArray(), hash)).isFalse();
        assertThat(PasswordHashes.create(password)).isNotEqualTo(hash);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "broken",
                "bcrypt$600000$AA==$AA==",
                "pbkdf2_sha256$599999$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "pbkdf2_sha256$600000$AA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "pbkdf2_sha256$600000$AAAAAAAAAAAAAAAAAAAAAA==$AA==",
                "pbkdf2_sha256$600000$!$!",
                "pbkdf2_sha256$2147483648$AA==$AA=="
            })
    void rejectsMalformedOrWeakHashes(String hash) {
        assertThatThrownBy(() -> PasswordHashes.validate(hash))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid password hash format");
    }
}
