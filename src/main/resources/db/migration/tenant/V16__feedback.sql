-- A customer's rating/comment against one completed booking, submitted through the public
-- feedback link (public.feedback_link resolves the token to tenant + booking). One row per
-- booking - a resubmission overwrites rather than accumulating history, same as a review
-- being edited rather than duplicated.
CREATE TABLE feedback (
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    booking_id   VARCHAR(36)  NOT NULL,
    client_id    VARCHAR(36)  NOT NULL,
    rating       INTEGER      NOT NULL,
    comment      VARCHAR(2000),
    submitted_at TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX idx_feedback_booking ON feedback (booking_id);
CREATE INDEX idx_feedback_client ON feedback (client_id);
