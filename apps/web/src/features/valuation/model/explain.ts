/** The causes of an MtM change in the order they are explained: trade events first, then market moves, then the rest. */
const ORDER = ['NEW', 'AMENDED', 'CANCELLED', 'DELIVERED', 'PRICE', 'FX', 'UNEXPLAINED'];

/** P&L explain components as chart bars in explanation order, leaving out causes with no effect; amounts are the server's. */
export function explainBars(
  components: Record<string, number>,
  label: (component: string) => string,
  order: string[] = ORDER,
): { component: string; amount: number }[] {
  const rank = (component: string) => (order.includes(component) ? order.indexOf(component) : order.length);
  return Object.entries(components)
    .filter(([, amount]) => amount !== 0)
    .sort(([a], [b]) => rank(a) - rank(b))
    .map(([component, amount]) => ({ component: label(component), amount }));
}
