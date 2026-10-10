import { registerSubjectScreens, subjectLink } from './subjects';

describe('subject links', () => {
  it('links only subjects whose screens exist', () => {
    expect(subjectLink('VALUATION_RUN', 'r1')).toBeNull();
    registerSubjectScreens('VALUATION_RUN');
    expect(subjectLink('VALUATION_RUN', 'r1')).toBe('/valuation/runs/r1');
    registerSubjectScreens('REGISTRY_INSTRUCTION');
    expect(subjectLink('REGISTRY_INSTRUCTION', 'x')).toBeNull();
  });
});
