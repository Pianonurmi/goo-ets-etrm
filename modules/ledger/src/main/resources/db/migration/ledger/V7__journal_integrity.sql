-- Journal integrity (increment 14, review findings D2, D4 and D8).

-- D2: entries keep the version of the hash payload they were written with. Existing entries are version 1; the
-- application writes version 2, which also covers the header and the base amounts (JournalChain).
ALTER TABLE journal_entry ADD COLUMN chain_version SMALLINT NOT NULL DEFAULT 1 CHECK (chain_version IN (1, 2));

-- D2: the only change an entry may undergo is POSTED -> REVERSED, setting reversed_by to the reversal entry that names
-- it. Every other column stays as written, and lines never change.
CREATE OR REPLACE FUNCTION journal_immutable() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'journal is append-only';
    END IF;
    IF TG_TABLE_NAME = 'journal_line' THEN
        RAISE EXCEPTION 'journal lines cannot be edited; post a reversal';
    END IF;
    IF OLD.status = 'POSTED' AND NEW.status = 'REVERSED'
        AND OLD.reversed_by IS NULL AND NEW.reversed_by IS NOT NULL
        AND (to_jsonb(NEW) - 'status' - 'reversed_by') = (to_jsonb(OLD) - 'status' - 'reversed_by')
        AND EXISTS (SELECT 1 FROM journal_entry r WHERE r.id = NEW.reversed_by AND r.reversal_of = OLD.id AND r.status = 'REVERSAL')
    THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'journal entries cannot be edited; post a reversal';
END $$;

-- D4: an entry is reversed at most once.
CREATE UNIQUE INDEX journal_entry_reversal_uq ON journal_entry (reversal_of) WHERE reversal_of IS NOT NULL;

-- D8: nothing is written into a hard-closed period, and the period is the posting date's month.
CREATE OR REPLACE FUNCTION journal_entry_period_open() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF NEW.period <> to_char(NEW.posting_date, 'YYYY-MM') THEN
        RAISE EXCEPTION 'journal entry period % does not match posting date %', NEW.period, NEW.posting_date;
    END IF;
    IF EXISTS (
        SELECT 1 FROM accounting_period p
        WHERE p.legal_entity_id = NEW.legal_entity_id AND p.period = NEW.period AND p.status = 'CLOSED'
    ) THEN
        RAISE EXCEPTION 'accounting period % is closed', NEW.period;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER journal_entry_period_open BEFORE INSERT ON journal_entry FOR EACH ROW EXECUTE FUNCTION journal_entry_period_open();

-- D8: at commit, the debits of every entry that received lines in the transaction equal its credits. The application
-- writes an entry's lines in one statement, so the check also holds for a caller without an explicit transaction.
CREATE OR REPLACE FUNCTION journal_entry_balanced() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    debits  NUMERIC;
    credits NUMERIC;
BEGIN
    SELECT coalesce(sum(debit), 0), coalesce(sum(credit), 0) INTO debits, credits
    FROM journal_line WHERE entry_id = NEW.entry_id;
    IF debits <> credits THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: debit %, credit %', NEW.entry_id, debits, credits;
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_line_balanced AFTER INSERT ON journal_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION journal_entry_balanced();

-- D9: the history of posting rule versions is append-only.
CREATE OR REPLACE FUNCTION posting_rule_history_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'posting rule history is append-only';
END $$;
CREATE TRIGGER posting_rule_history_append_only BEFORE UPDATE OR DELETE ON posting_rule_history
    FOR EACH ROW EXECUTE FUNCTION posting_rule_history_append_only();
