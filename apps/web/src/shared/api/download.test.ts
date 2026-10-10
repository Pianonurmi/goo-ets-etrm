import { fileNameFrom } from './download';

describe('fileNameFrom', () => {
  it('reads plain and encoded file names', () => {
    expect(fileNameFrom('attachment; filename="gl-export-7.csv"', 'x.csv')).toBe('gl-export-7.csv');
    expect(fileNameFrom("attachment; filename*=UTF-8''r%C3%A4port.csv", 'x.csv')).toBe('räport.csv');
    expect(fileNameFrom(null, 'fallback.csv')).toBe('fallback.csv');
  });
});
