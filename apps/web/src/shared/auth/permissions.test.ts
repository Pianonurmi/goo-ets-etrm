import { can, PERMISSIONS } from './permissions';

describe('permissions', () => {
  it('lets the listed roles and ADMIN call an operation', () => {
    expect(PERMISSIONS.referenceData_createLegalEntity).toEqual(['ADMIN']);
    expect(can(['ADMIN'], 'referenceData_createLegalEntity')).toBe(true);
    expect(can(['TRADER'], 'referenceData_createLegalEntity')).toBe(false);
  });

  it('leaves unlisted operations open to every signed-in user', () => {
    expect(can(['AUDITOR'], 'referenceData_legalEntities')).toBe(true);
  });
});
