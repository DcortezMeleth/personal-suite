-- Add annual rate field to treasury_bonds for server-side interest computation.
-- The rate is the nominal annual rate in percent (e.g., 6.85 for 6.85 %).
-- For indexed bonds (COI, EDO, ROS) this should be the *effective* rate for the
-- current year; the user can update it when rates are announced.
ALTER TABLE treasury_bonds
    ADD COLUMN annual_rate_pct NUMERIC(7, 4) NOT NULL DEFAULT 0;
