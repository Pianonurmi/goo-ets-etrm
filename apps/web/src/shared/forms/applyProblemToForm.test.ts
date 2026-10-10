import { ProblemError } from '@/shared/api/problem';
import { applyProblemToForm, toFormPath } from './applyProblemToForm';

const problem = new ProblemError({
  type: 'urn:etrm:problem:validation-failed',
  title: 'validation-failed',
  status: 422,
  violations: [
    { field: 'legs[0].quantity', message: 'must be positive' },
    { field: 'tradeDate', message: 'must not be in the future' },
    { field: 'internalOnly', message: 'cannot be set' },
  ],
});

describe('applyProblemToForm', () => {
  it('converts Spring binding paths to form paths', () => {
    expect(toFormPath('legs[0].deliveries[12].quantity')).toBe('legs.0.deliveries.12.quantity');
    expect(toFormPath('reason')).toBe('reason');
  });

  it('places violations on known fields and returns the rest', () => {
    const errors: Record<string, string> = {};
    const unplaced = applyProblemToForm(
      { setFieldError: (path, message) => (errors[path] = message) },
      problem,
      new Set(['legs', 'tradeDate']),
    );
    expect(errors).toEqual({ 'legs.0.quantity': 'must be positive', tradeDate: 'must not be in the future' });
    expect(unplaced).toEqual([{ field: 'internalOnly', message: 'cannot be set' }]);
  });

  it('places everything when the form does not list its fields', () => {
    const paths: string[] = [];
    applyProblemToForm({ setFieldError: (path) => paths.push(path) }, problem);
    expect(paths).toHaveLength(3);
  });
});
