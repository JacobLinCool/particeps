import type { StepState } from '$lib/ui/types';
import type { IconRef } from '$lib/ui/icons';

export type StepId = 'study' | 'keys' | 'overview' | 'sign' | 'files' | 'read';
export type StudySection = 'details' | 'data' | 'activities' | 'rules' | 'delivery';
export interface StepDefinition { id: StepId; icon: IconRef; paths: readonly string[] }

export const STEPS: readonly StepDefinition[] = [
  { id: 'study', icon: 'document', paths: ['schema_version', 'issued_at', 'expires_at',
    'minimum_client_version', 'title', 'researcher', 'purpose', 'duration_hours', 'consent',
    'collectors', 'assigned_participant_id', 'surveys', 'interventions', 'automations',
    'traffic_shaping', 'storage', 'upload'] },
  { id: 'keys', icon: 'key', paths: ['signer', 'export', 'signing_private_key', 'export_private_key'] },
  { id: 'overview', icon: 'clock', paths: ['review.blinding'] },
  { id: 'sign', icon: 'seal', paths: [] },
  { id: 'files', icon: 'send', paths: [] },
  { id: 'read', icon: 'unlock', paths: [] }
];
const OWNER = new Map(STEPS.flatMap(step => step.paths.map(path => [path, step.id] as const)));
const EXACT = new Map<string, StepId>([
  ['signer.key_id', 'sign'], ['export.researcher_key_id', 'sign'], ['review.blinding', 'overview']
]);
export function stepForPath(path: string): StepId {
  return EXACT.get(path) ?? OWNER.get(path.split('.')[0]) ?? 'sign';
}
export function sectionForPath(path: string): StudySection {
  switch (path.split('.')[0]) {
    case 'collectors': case 'storage': return 'data';
    case 'surveys': case 'interventions': return 'activities';
    case 'automations': case 'traffic_shaping': return 'rules';
    case 'upload': return 'delivery';
    default: return 'details';
  }
}
export type { StepState };
