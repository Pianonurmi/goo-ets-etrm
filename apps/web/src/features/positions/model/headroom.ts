import type { Schema } from '@/shared/api/types';

export type HeadroomTable = Schema<'HeadroomTable'>;

/** One grid row: the bucket and vintage with what is held, and the headroom of each delivery date as `d0`, `d1`, ... */
export type HeadroomGridRow = {
  key: string;
  bucketCode: string;
  vintageYear: number;
  heldMwh: number;
  /** Supply that is not held yet at the table's tier (purchases, expected issuance); 0 at the held tier. */
  futureMwh: number;
} & Record<`d${number}`, number>;

/** The field of delivery date number [index] in a [HeadroomGridRow]. */
export const dateField = (index: number): `d${number}` => `d${index}`;

/** Flattens the table so the grid can read each delivery date's cell by field; a missing cell reads as 0. */
export function headroomRows(table: HeadroomTable): HeadroomGridRow[] {
  return table.rows.map((row) => {
    const cells: Record<`d${number}`, number> = {};
    table.deliveryDates.forEach((_, index) => {
      cells[dateField(index)] = row.headroomMwh[index] ?? 0;
    });
    return {
      key: `${row.bucketCode}-${row.vintageYear}`,
      bucketCode: row.bucketCode,
      vintageYear: row.vintageYear,
      heldMwh: row.heldMwh,
      futureMwh: row.futureMwh,
      ...cells,
    };
  });
}
