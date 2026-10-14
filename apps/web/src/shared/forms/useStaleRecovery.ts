import { useEffect, useRef, useState } from 'react';
import { isStaleVersion } from '@/shared/api/problem';
import { notifyProblem } from '@/shared/components/notify';
import { rebaseEdits, staleChanges, type Draft, type FieldChange } from './staleVersion';

/** The record as the server has it now, and what changed in the fields the form shows. */
export interface StaleConflict<R> {
  fresh: R;
  changes: FieldChange[];
}

/**
 * Recovery from a 409 `stale-version` on a form (W5). The form works on `record` (the version it was opened on,
 * which stays put even when a query behind the screen refetches). When a save is refused, `handle` fetches the
 * record again and reports `conflict`; `keepEdits` then re-applies the user's edited fields on top of the server's
 * version and moves `record` to it, so the next save carries the new version number.
 */
export function useStaleRecovery<R, D extends Draft>({
  initial,
  toDraft,
  values,
  setValues,
  fetchCurrent,
}: {
  initial: R;
  toDraft: (record: R) => D;
  values: D;
  setValues: (draft: D) => void;
  fetchCurrent: () => Promise<R>;
}) {
  const [record, setRecord] = useState(initial);
  const [conflict, setConflict] = useState<StaleConflict<R> | null>(null);
  const [checking, setChecking] = useState(false);
  // A save answers after the user may have kept typing; the comparison uses what is in the form at that moment.
  const latest = useRef({ record, values, toDraft, fetchCurrent });
  useEffect(() => {
    latest.current = { record, values, toDraft, fetchCurrent };
  });

  /** True when the error was a stale version and recovery has started; the caller shows nothing else for it. */
  const handle = (error: unknown): boolean => {
    if (!isStaleVersion(error)) return false;
    setChecking(true);
    void latest.current
      .fetchCurrent()
      .then((fresh) => {
        const { record: loaded, values: mine, toDraft: draftOf } = latest.current;
        setConflict({ fresh, changes: staleChanges(draftOf(loaded), mine, draftOf(fresh)) });
      })
      .catch((failure: unknown) => notifyProblem(failure))
      .finally(() => setChecking(false));
    return true;
  };

  const adopt = (next: D) => {
    if (!conflict) return;
    setValues(next);
    setRecord(conflict.fresh);
    setConflict(null);
  };

  return {
    record,
    conflict,
    checking,
    handle,
    /** Reload: the server's version, with the fields the user edited kept. */
    keepEdits: () => conflict && adopt(rebaseEdits(toDraft(record), values, toDraft(conflict.fresh))),
    /** Reload and drop the user's edits. */
    discardEdits: () => conflict && adopt(toDraft(conflict.fresh)),
  };
}
