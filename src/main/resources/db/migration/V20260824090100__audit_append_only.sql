-- ═════════════════════════════════════════════════════════════════════════════════════════════════
-- Phase 0a — the audit trail becomes append-only (BRD FR006/FR123/FR156, NFR "Logging and Audit")
--
-- The requirement is an audit log "stored immutably". Until now `audit_logs` was an ordinary table: the
-- application only ever inserted, but nothing said it had to, and an immutability claim that rests on
-- every future writer remembering is not immutability.
--
-- A statement-level trigger rather than a row-level one: nothing here needs the row, and one raise per
-- statement is cheaper than one per row on a mistaken bulk UPDATE.
--
-- <p><strong>Retention.</strong> This deliberately blocks DELETE too, which means retention cannot be a
-- DELETE. When these rows need aging out, the answer is a partitioned table and a DROP of the oldest
-- partition — a schema change made by whoever owns the database, not something a service can do by
-- accident. Nothing in this codebase deletes audit rows today.
-- ═════════════════════════════════════════════════════════════════════════════════════════════════

CREATE OR REPLACE FUNCTION audit_logs_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only — % is not permitted', tg_op
        USING errcode = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_no_update
    BEFORE UPDATE ON audit_logs
    EXECUTE FUNCTION audit_logs_append_only();

CREATE TRIGGER trg_audit_logs_no_delete
    BEFORE DELETE ON audit_logs
    EXECUTE FUNCTION audit_logs_append_only();

COMMENT ON TABLE audit_logs IS
    'Append-only. UPDATE and DELETE are refused by trigger; retention is by partition drop, not by DELETE.';
