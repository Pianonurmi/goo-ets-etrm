import { describe, expect, it } from 'vitest';
import { exercisedOptionNumber } from './exercise';

describe('exercisedOptionNumber', () => {
  it('reads the option number after the prefix', () => {
    expect(exercisedOptionNumber('exercise:T-2026-000123')).toBe('T-2026-000123');
  });

  it('ignores other references', () => {
    expect(exercisedOptionNumber('BROKER-17')).toBeNull();
    expect(exercisedOptionNumber(null)).toBeNull();
    expect(exercisedOptionNumber(undefined)).toBeNull();
    expect(exercisedOptionNumber('Exercise:T-1')).toBeNull();
  });

  it('ignores an empty number', () => {
    expect(exercisedOptionNumber('exercise:')).toBeNull();
    expect(exercisedOptionNumber('exercise:  ')).toBeNull();
  });
});
