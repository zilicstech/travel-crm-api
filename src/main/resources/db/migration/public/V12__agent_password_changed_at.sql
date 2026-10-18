-- A password reset must end the sessions issued before it (JWTs are otherwise valid until expiry).
ALTER TABLE agent ADD COLUMN password_changed_at TIMESTAMPTZ;
