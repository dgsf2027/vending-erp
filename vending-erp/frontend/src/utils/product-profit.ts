/** 档案参考单件利润(售价 - 拿货价),缺值显示未知;零成本有效。 */
export function productProfit(price: unknown, cost: unknown): number | null {
  const missing = (v: unknown) => v == null || (typeof v === 'string' && !v.trim())
  if (missing(price) || missing(cost)) return null
  const p = Number(price), c = Number(cost)
  return Number.isFinite(p) && Number.isFinite(c)
    ? Math.round((p - c + Number.EPSILON) * 100) / 100 : null
}
