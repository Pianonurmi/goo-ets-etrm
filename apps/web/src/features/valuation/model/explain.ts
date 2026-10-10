/** P&L explain components as chart bars, largest effect first; amounts are the server's. */
export function explainBars(components: Record<string, number>): { component: string; amount: number }[] {
  return Object.entries(components)
    .map(([component, amount]) => ({ component: component.replace(/_/g, ' ').toLowerCase(), amount }))
    .sort((a, b) => Math.abs(b.amount) - Math.abs(a.amount));
}
