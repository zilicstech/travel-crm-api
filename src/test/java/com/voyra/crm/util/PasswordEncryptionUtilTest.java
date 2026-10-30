package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordEncryptionUtilTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString(new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32});

    @Test
    void encryptsWithAPrefixAndDecryptsBack() {
        String enc = PasswordEncryptionUtil.encrypt("test-secret", KEY);

        assertThat(enc).startsWith("ENC:").isNotEqualTo(PasswordEncryptionUtil.encrypt("test-secret", KEY));
        assertThat(PasswordEncryptionUtil.isEncrypted(enc)).isTrue();
        assertThat(PasswordEncryptionUtil.decrypt(enc, KEY)).isEqualTo("test-secret");
    }

    @Test
    void onlyAPrefixedValueCountsAsEncrypted() {
        assertThat(PasswordEncryptionUtil.isEncrypted(null)).isFalse();
        assertThat(PasswordEncryptionUtil.isEncrypted("plain")).isFalse();
    }

    @Test
    void theWrongKeyOrABadKeyFails() {
        String enc = PasswordEncryptionUtil.encrypt("test-secret", KEY);

        assertThatThrownBy(() -> PasswordEncryptionUtil.decrypt(enc, OTHER_KEY))
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to decrypt value");
        assertThatThrownBy(() -> PasswordEncryptionUtil.encrypt("x", Base64.getEncoder().encodeToString(new byte[5])))
                .isInstanceOf(IllegalStateException.class).hasMessage("Failed to encrypt value");
    }
}
