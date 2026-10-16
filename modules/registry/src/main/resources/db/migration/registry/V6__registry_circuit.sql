-- Open circuit per registry (increment 16 M3): consecutive transport failures (the registry did not answer, or
-- answered that it did not take the request) pause its dispatch. Paused, the dispatcher sends one instruction as a
-- probe after the cool-down; an answer closes the circuit, as does an operator's resume.
CREATE TABLE registry_circuit (
    registry_code        TEXT        NOT NULL PRIMARY KEY,
    consecutive_failures INT         NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    last_failure         TEXT,
    opened_at            TIMESTAMPTZ,
    probe_after          TIMESTAMPTZ,
    resumed_by           TEXT,
    resumed_at           TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL,
    CONSTRAINT registry_circuit_open CHECK ((opened_at IS NULL) = (probe_after IS NULL))
);
