import type { ProblemError } from '@/shared/api/problem';

/** The part of a Mantine form this needs, so tests can pass a plain object. */
export interface FieldErrorSink {
  setFieldError: (path: string, error: string) => void;
}

/** `legs[0].quantity` (Spring binding) and `legs.0.quantity` (Mantine form paths) name the same field. */
export function toFormPath(field: string): string {
  return field.replace(/\[(\d+)\]/g, '.$1');
}

/**
 * Shows the violations of a 422 problem on the form's fields. Returns the violations it could not place, so the
 * caller can show them in a notification; fields that the form does not know are reported back as well.
 */
export function applyProblemToForm(
  form: FieldErrorSink,
  error: ProblemError,
  knownFields?: ReadonlySet<string>,
): { field: string; message: string }[] {
  const unplaced: { field: string; message: string }[] = [];
  for (const violation of error.problem.violations ?? []) {
    const path = toFormPath(violation.field);
    const root = path.split('.')[0] ?? path;
    if (knownFields && !knownFields.has(root)) {
      unplaced.push(violation);
    } else {
      form.setFieldError(path, violation.message);
    }
  }
  return unplaced;
}
