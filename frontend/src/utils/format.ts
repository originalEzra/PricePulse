const priceFormatter = new Intl.NumberFormat('zh-CN', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

export function formatPrice(value: number | null | undefined): string {
  return value == null || !Number.isFinite(Number(value)) ? '—' : priceFormatter.format(Number(value))
}
