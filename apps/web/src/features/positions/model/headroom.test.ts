import { dateField, headroomRows, type HeadroomTable } from './headroom';

const table: HeadroomTable = {
  holderEntityId: '00000000-0000-7000-8000-000000000001',
  stamp: 's1',
  computedAt: '2026-10-12T08:00:00Z',
  deliveryDates: ['2026-10-31', '2026-11-30'],
  rows: [
    { bucketCode: 'FI_HYDRO', vintageYear: 2025, heldMwh: 500, headroomMwh: [500, 450] },
    { bucketCode: 'SE_WIND', vintageYear: 2026, heldMwh: 80, headroomMwh: [80] },
  ],
};

describe('headroomRows', () => {
  it('gives each delivery date its own field in the bucket and vintage row', () => {
    const rows = headroomRows(table);
    expect(rows).toHaveLength(2);
    expect(rows[0]).toMatchObject({
      key: 'FI_HYDRO-2025',
      bucketCode: 'FI_HYDRO',
      vintageYear: 2025,
      heldMwh: 500,
      d0: 500,
      d1: 450,
    });
  });

  it('reads a missing cell as 0', () => {
    expect(headroomRows(table)[1]?.[dateField(1)]).toBe(0);
  });

  it('has no rows for an empty table', () => {
    expect(headroomRows({ ...table, rows: [] })).toEqual([]);
  });
});
