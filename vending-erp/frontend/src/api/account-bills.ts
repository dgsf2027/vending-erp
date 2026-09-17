import request from '@/utils/request'
import type { PageResult } from '@/api/money'
export interface AccountBill {
  id?: number; billDate: string; incomeAmount: number | string; feeAmount: number | string
  sourceAccount: string; ownerName: string; sourceRole: string; channel: string; merchantNo: string
  arrivalDate: string | null; settlementAmount: number | string; settlementStatus: string; settlementInfo: string
  accountId?: number | null; sourceFile: string; sourceRow: number
}
export interface BillTotals {
  count: number; incomeAmount: number | string; feeAmount: number | string
  settlementAmount: number | string; successfulSettlementAmount: number | string
}
export interface BillPreview { rows: AccountBill[]; newCount: number; duplicateCount: number; errors: string[]; totals: BillTotals }
export interface BillQuery { current: number; size: number; from?: string; to?: string; accountId?: number; status?: string }
function upload(file: File, accountId?: number | null) {
  const data = new FormData(); data.append('file', file)
  if (accountId != null) data.append('accountId', String(accountId))
  return data
}
export function previewAccountBills(file: File, accountId?: number | null): Promise<BillPreview> {
  return request.post('/v1/money/account-bills/preview', upload(file, accountId), { timeout: 60000 })
}
export function importAccountBills(file: File, accountId?: number | null): Promise<{ inserted: number; skipped: number }> {
  return request.post('/v1/money/account-bills/import', upload(file, accountId), { timeout: 60000 })
}
export function listAccountBills(params: BillQuery): Promise<{ page: PageResult<AccountBill>; totals: BillTotals }> {
  return request.get('/v1/money/account-bills', { params })
}
