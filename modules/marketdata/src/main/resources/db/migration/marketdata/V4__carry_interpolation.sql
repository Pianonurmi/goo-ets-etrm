-- How a forward curve is read beyond its last pillar: flat (LINEAR, as before) or extended at the last segment's carry
-- rate (CARRY). Between pillars both are linear in days, which under simple compounding is a constant carry rate. The
-- snapshot keeps the method it was built with, so an official price can be reproduced after the curve changes.
ALTER TABLE curve ADD COLUMN interpolation TEXT NOT NULL DEFAULT 'LINEAR' CHECK (interpolation IN ('LINEAR', 'CARRY'));
ALTER TABLE curve ADD CONSTRAINT curve_carry_ck CHECK (interpolation = 'LINEAR' OR kind = 'EUA_FORWARD');
ALTER TABLE curve_snapshot ADD COLUMN interpolation TEXT NOT NULL DEFAULT 'LINEAR' CHECK (interpolation IN ('LINEAR', 'CARRY'));
