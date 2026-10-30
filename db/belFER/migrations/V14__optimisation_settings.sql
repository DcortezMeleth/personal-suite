-- What a good timetable is, as distinct from a legal one.
--
-- Two kinds of setting, kept apart because the difference matters to whoever
-- sets them (ADR 0005). A bound is absolute: FET is told to satisfy it and a
-- week that cannot is no week. A weight is a strength between 0 and 100: FET
-- tries, gives up when it must, and no value can make generation fail.
--
-- Which is which is not ours to choose. FET refuses a weight below 100 on the
-- gap constraints and on the daily minimum, and accepts one on the daily
-- maximum and on both room preferences. The split below is that refusal.

ALTER TABLE school
    -- Bound. How many free hours a teacher may have across the whole week.
    -- Null means unbounded, which is where every school starts: two a day
    -- over five days already permits ten, so this only ever tightens.
    ADD COLUMN max_teacher_gaps_per_week INT,

    -- Bound. The short end of a class's day. Paired with the maximum below,
    -- this is how "longest minus shortest is at most three" gets said, since
    -- FET has no way to speak about a spread.
    ADD COLUMN min_class_hours_daily INT,

    -- Preference. The long end of the same, with its strength.
    ADD COLUMN max_class_hours_daily INT,
    ADD COLUMN class_day_weight INT NOT NULL DEFAULT 0,

    -- Preference. A lesson in its teacher's own room.
    ADD COLUMN home_room_weight INT NOT NULL DEFAULT 0,

    -- Preference. A subject in a room of its kind, where the subject says the
    -- requirement is soft. Subjects that say it is hard are unaffected and
    -- stay at full strength.
    ADD COLUMN soft_room_weight INT NOT NULL DEFAULT 0;

ALTER TABLE school
    ADD CONSTRAINT school_max_teacher_gaps_per_week_sane
        CHECK (max_teacher_gaps_per_week IS NULL OR max_teacher_gaps_per_week >= 0),
    ADD CONSTRAINT school_class_hours_daily_sane
        CHECK (min_class_hours_daily IS NULL OR max_class_hours_daily IS NULL
               OR min_class_hours_daily <= max_class_hours_daily),
    ADD CONSTRAINT school_weights_are_percentages
        CHECK (class_day_weight BETWEEN 0 AND 100
           AND home_room_weight BETWEEN 0 AND 100
           AND soft_room_weight BETWEEN 0 AND 100);

-- Everything off by default. A school upgrading to this version generates
-- exactly the week it generated before until somebody asks for something
-- different, which is the only safe way to add an optimiser to a running tool.
