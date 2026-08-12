package com.voyra.crm.security;

import com.voyra.crm.util.PasswordEncryptionUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Reversible AES-256-GCM password encoder. Chosen ONLY because Owners must be able to
 * retrieve and hand out an Agent's generated password (there is no self-service invite/reset
 * flow in v1 scope). Falls back to BCrypt verification so any BCrypt-hashed value still
 * matches, in case an account is migrated to self-service auth later.
 */
public class AesPasswordEncoder implements PasswordEncoder {

    private final String encryptionKeyBase64;
    private final BCryptPasswordEncoder bcryptFallback = new BCryptPasswordEncoder(10);

    public AesPasswordEncoder(String encryptionKeyBase64) {
        this.encryptionKeyBase64 = encryptionKeyBase64;
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return PasswordEncryptionUtil.encrypt(rawPassword.toString(), encryptionKeyBase64);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (PasswordEncryptionUtil.isEncrypted(encodedPassword)) {
            return PasswordEncryptionUtil.decrypt(encodedPassword, encryptionKeyBase64)
                    .equals(rawPassword.toString());
        }
        return bcryptFallback.matches(rawPassword, encodedPassword);
    }

    /** Retrieval path - callers must guard this behind an ADMIN-role, same-tenant check and log every call. */
    public String decode(String encodedPassword) {
        return PasswordEncryptionUtil.decrypt(encodedPassword, encryptionKeyBase64);
    }
}
