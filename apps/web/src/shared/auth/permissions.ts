import type { OperationId, Role } from '@/shared/api/types';

/**
 * Roles that may call an operation, mirroring the services' role checks (ADR 0028). ADMIN may call everything;
 * an operation that is not listed is open to every signed-in user. The API stays the authority: this only decides
 * whether the UI offers an action.
 */
export const PERMISSIONS: Partial<Record<OperationId, readonly Role[]>> = {
  referenceData_createLegalEntity: ['ADMIN'],
  trade_capture: ['TRADER', 'MIDDLE_OFFICE'],
  trade_amend: ['TRADER', 'MIDDLE_OFFICE'],
  trade_cancel: ['TRADER', 'MIDDLE_OFFICE'],
  trade_approve: ['MIDDLE_OFFICE', 'RISK'],
  trade_reject: ['MIDDLE_OFFICE', 'RISK'],
  trade_confirm: ['MIDDLE_OFFICE', 'BACK_OFFICE'],
  trade_recordDelivery: ['MIDDLE_OFFICE', 'BACK_OFFICE'],
  trade_settle: ['MIDDLE_OFFICE', 'BACK_OFFICE'],
  confirmation_issue: ['MIDDLE_OFFICE', 'BACK_OFFICE'],
  allocation_propose: ['TRADER', 'MIDDLE_OFFICE', 'BACK_OFFICE'],
  allocation_whatIf: ['TRADER', 'MIDDLE_OFFICE', 'BACK_OFFICE'],
  allocation_approve: ['MIDDLE_OFFICE', 'BACK_OFFICE'],
  allocation_reject: ['MIDDLE_OFFICE', 'BACK_OFFICE'],
  allocation_cancel: ['TRADER', 'MIDDLE_OFFICE', 'BACK_OFFICE'],
  marketData_recordQuote: ['MIDDLE_OFFICE', 'RISK'],
  marketData_acknowledge: ['MIDDLE_OFFICE', 'RISK'],
  marketData_recordFxRate: ['MIDDLE_OFFICE', 'RISK'],
  marketData_pullEcb: ['MIDDLE_OFFICE', 'RISK'],
  marketData_importFile: ['MIDDLE_OFFICE', 'RISK'],
  marketData_createInstrument: ['MIDDLE_OFFICE', 'RISK'],
  curve_create: ['MIDDLE_OFFICE', 'RISK'],
  curve_build: ['MIDDLE_OFFICE', 'RISK'],
  curve_submit: ['MIDDLE_OFFICE', 'RISK'],
  curve_override: ['MIDDLE_OFFICE', 'RISK'],
  valuation_run: ['MIDDLE_OFFICE', 'RISK'],
  valuation_submit: ['MIDDLE_OFFICE', 'RISK'],
  risk_requestLimit: ['RISK'],
  risk_measure: ['RISK'],
  risk_requestCreditSupport: ['RISK', 'BACK_OFFICE'],
  etsCompliance_register: ['MIDDLE_OFFICE', 'BACK_OFFICE'],
  etsCompliance_recordEmissions: ['MIDDLE_OFFICE', 'BACK_OFFICE', 'RISK'],
  etsCompliance_recordVerifiedEmissions: ['MIDDLE_OFFICE', 'BACK_OFFICE', 'RISK'],
  etsCompliance_setFreeAllocation: ['MIDDLE_OFFICE', 'BACK_OFFICE'],
  etsCompliance_requestSurrender: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
  ledger_postManual: ['ACCOUNTANT', 'BACK_OFFICE'],
  ledger_reverse: ['ACCOUNTANT', 'BACK_OFFICE'],
  ledger_closePeriod: ['ACCOUNTANT'],
  ledger_reopenPeriod: ['ACCOUNTANT'],
  ledger_hardCloseBlockers: ['ACCOUNTANT'],
  ledger_export: ['ACCOUNTANT'],
  ledger_exportCsv: ['ACCOUNTANT'],
  ledger_delivered: ['ACCOUNTANT'],
  ledger_acknowledge: ['ACCOUNTANT'],
  ledger_reject: ['ACCOUNTANT'],
  ledger_importBalances: ['ACCOUNTANT'],
  ledger_exports: ['ACCOUNTANT', 'AUDITOR'],
  ledger_reconciliation: ['ACCOUNTANT', 'AUDITOR'],
  ledger_upsertRule: ['ACCOUNTANT'],
  periodEnd_run: ['ACCOUNTANT'],
  periodEnd_requestPolicyChange: ['ACCOUNTANT'],
  reporting_exports: ['AUDITOR', 'ACCOUNTANT', 'RISK', 'MIDDLE_OFFICE'],
  reporting_emirFieldRules: ['MIDDLE_OFFICE', 'RISK', 'ACCOUNTANT', 'AUDITOR'],
  reporting_reportError: ['MIDDLE_OFFICE'],
  inventory_receive: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
  inventory_split: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
  inventory_reserve: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
  inventory_release: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
  inventory_cancel: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
  inventory_withdraw: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
  inventory_correct: ['BACK_OFFICE', 'REGISTRY_OPERATOR'],
};

export function can(roles: readonly Role[], operation: OperationId): boolean {
  if (roles.includes('ADMIN')) return true;
  const allowed = PERMISSIONS[operation];
  return allowed === undefined || allowed.some((role) => roles.includes(role));
}
