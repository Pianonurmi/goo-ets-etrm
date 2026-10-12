import type { Body, Schema } from '@/shared/api/types';
import type { TradeFormValues, TradeView } from './tradeForm';

export type HeadroomCheck = Body<'HeadroomCheck'>;
export type HeadroomResult = Schema<'HeadroomResult'>;
export type DemandHeadroom = Schema<'DemandHeadroom'>;

const POSITIVE = /^\d+(\.\d+)?$/;

/** The key of a delivery row in a check: `<leg>-<delivery>`, also the `ref` the server echoes back. */
export const demandRef = (leg: number, delivery: number) => `${leg}-${delivery}`;

/**
 * The check for the sale deliveries of a form: every delivery of a GoO SELL leg with a usable quantity and date is one
 * demand. Buys, allowance trades, futures and half-filled rows ask nothing; `null` when nothing remains. Quantities are
 * rounded up to whole MWh, as the server counts whole certificates.
 */
export function headroomRequest(
  values: TradeFormValues,
  entityId: string | undefined,
  excludeSourceRefs: readonly string[] = [],
): HeadroomCheck | null {
  if (!entityId || values.productFamily !== 'GUARANTEE_OF_ORIGIN' || values.instrumentType === 'FUTURE') return null;
  const demands: HeadroomCheck['demands'] = [];
  values.legs.forEach((leg, l) => {
    if (leg.side !== 'SELL') return;
    leg.deliveries.forEach((delivery, d) => {
      const quantity = delivery.quantity.trim();
      if (!POSITIVE.test(quantity) || Number(quantity) <= 0 || !delivery.deliveryDate) return;
      demands.push({
        criterion: leg.criterion,
        quantityMwh: Math.ceil(Number(quantity)),
        deliveryDate: delivery.deliveryDate,
        ref: demandRef(l, d),
      });
    });
  });
  if (demands.length === 0) return null;
  const body: HeadroomCheck = { holderEntityId: entityId, demands };
  if (excludeSourceRefs.length > 0) body.excludeSourceRefs = [...excludeSourceRefs];
  return body;
}

/**
 * The delivery schedules of an existing GoO trade's sale legs. An amendment replaces them, so they must not count as
 * demand against the supply the amended terms compete for.
 */
export function ownScheduleIds(trade: TradeView | undefined): string[] {
  if (!trade || trade.productFamily !== 'GUARANTEE_OF_ORIGIN') return [];
  return trade.legs.filter((leg) => leg.side === 'SELL').flatMap((leg) => leg.deliveries.map((d) => d.id));
}

/** The result for each delivery row, keyed by [demandRef]. */
export function headroomByRef(result: HeadroomResult | undefined): Map<string, DemandHeadroom> {
  const byRef = new Map<string, DemandHeadroom>();
  for (const demand of result?.demands ?? []) if (demand.ref) byRef.set(demand.ref, demand);
  return byRef;
}

/**
 * Set when every delivery fits on its own but the deliveries of the trade together ask for more than the same
 * certificates can serve (they compete for them).
 */
export function jointShortfall(result: HeadroomResult | undefined): { requestedMwh: number; jointMwh: number } | null {
  if (!result || result.jointMwh == null || result.demands.length < 2) return null;
  if (!result.demands.every((demand) => demand.fits)) return null;
  return result.jointMwh < result.requestedMwh
    ? { requestedMwh: result.requestedMwh, jointMwh: result.jointMwh }
    : null;
}

/** The supply that runs out, scarcest first, at most [limit] entries. */
export function bindingSupplies(demand: DemandHeadroom, limit = 3): DemandHeadroom['binding'] {
  return [...demand.binding].sort((a, b) => a.eligibleMwh - b.eligibleMwh).slice(0, limit);
}
