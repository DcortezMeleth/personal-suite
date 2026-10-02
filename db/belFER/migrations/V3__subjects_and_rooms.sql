-- Subjects and rooms.
--
-- A subject carries two scheduling-relevant flags beyond its name:
--
--   * `optional` marks religia, etyka and their kin. Not every student
--     attends, so these are placed first or last in the day, letting the rest
--     arrive late or leave early. It is a flag rather than a hard-coded list of
--     subject names because the school decides what counts.
--
--   * `required_room_kind` points at a specialist room. The requirement is
--     normally honoured but can be waived per subject, because specialist
--     rooms run short and the alternative to an ordinary room is no lesson at
--     all. `room_requirement_hard` is what the school sets when it would
--     rather the plan failed than the lesson moved.
--
-- Rooms are a scheduling resource in their own right. A teacher's assigned
-- room means priority, not exclusivity: several teachers may share one, and
-- not every teacher has one, so the solver assigns the room per lesson.

CREATE TABLE room_kind (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id  UUID NOT NULL REFERENCES school (id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    UNIQUE (school_id, name)
);

CREATE TABLE room (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id     UUID NOT NULL REFERENCES school (id) ON DELETE CASCADE,
    -- The number is how the school refers to a room; the name is optional
    -- colour ("sala gimnastyczna — część A").
    number        TEXT NOT NULL,
    name          TEXT,
    -- Specialist rooms are sometimes too small for a whole class, which is one
    -- of the reasons a lesson gets split in the first place.
    fits_whole_class BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (school_id, number)
);

-- A room can satisfy more than one requirement: the gym's sections are all
-- PE rooms, and a language room may double as an ordinary one.
CREATE TABLE room_kind_assignment (
    room_id      UUID NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    room_kind_id UUID NOT NULL REFERENCES room_kind (id) ON DELETE CASCADE,
    PRIMARY KEY (room_id, room_kind_id)
);

CREATE TABLE subject (
    id                    UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id             UUID    NOT NULL REFERENCES school (id) ON DELETE CASCADE,
    -- The short code the arkusz uses ("j.angielski", "r_matematyka").
    code                  TEXT    NOT NULL,
    name                  TEXT    NOT NULL,
    optional              BOOLEAN NOT NULL DEFAULT FALSE,
    required_room_kind_id UUID    REFERENCES room_kind (id) ON DELETE SET NULL,
    -- Only meaningful when a room kind is required.
    room_requirement_hard BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (school_id, code)
);
