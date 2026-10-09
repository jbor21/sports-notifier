-- When a follow's switches last changed. The Phase 4.1 checker freezes follows for a run: a switch
-- flipped and flipped back during the run would leave the alerts equal, but not this.
ALTER TABLE follows ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
