-- Move delFIN out of `public` and into a `delfin` schema.
--
-- Each service in the suite owns a schema, so that two services sharing one
-- Postgres instance cannot collide on table names or on Flyway's version
-- counter. belFER was built that way from the start; delFIN predates the
-- convention and had been sitting in `public`.
--
-- This is a one-off maintenance script rather than a Flyway migration, because
-- it has to move flyway_schema_history itself — which Flyway cannot do from
-- inside a migration it is recording in that very table. Run it once, with the
-- application stopped, before deploying the config change that points delFIN at
-- the new schema.
--
--   psql -h localhost -U delfin -d delfin -f <this file>
--
-- The object lists are discovered rather than hard-coded: enumerating 14 tables
-- and 7 enum types by hand is exactly how one gets left behind.

BEGIN;

CREATE SCHEMA IF NOT EXISTS delfin;

DO $$
DECLARE
    obj record;
BEGIN
    FOR obj IN
        SELECT tablename FROM pg_tables WHERE schemaname = 'public'
    LOOP
        EXECUTE format('ALTER TABLE public.%I SET SCHEMA delfin', obj.tablename);
    END LOOP;

    FOR obj IN
        SELECT viewname FROM pg_views WHERE schemaname = 'public'
    LOOP
        EXECUTE format('ALTER VIEW public.%I SET SCHEMA delfin', obj.viewname);
    END LOOP;

    -- Enum types do not follow their tables. Leaving them behind still works,
    -- because the columns keep referring to public.<type>, which is precisely
    -- what makes it easy to miss until someone drops the public schema.
    FOR obj IN
        SELECT t.typname
        FROM pg_type t
        JOIN pg_namespace n ON n.oid = t.typnamespace
        WHERE n.nspname = 'public' AND t.typtype = 'e'
    LOOP
        EXECUTE format('ALTER TYPE public.%I SET SCHEMA delfin', obj.typname);
    END LOOP;
END
$$;

COMMIT;
