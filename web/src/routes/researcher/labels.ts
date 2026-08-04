/**
 * A schema path, named the way the control that owns it is named.
 *
 * The issue list is a list of buttons a researcher clicks to get to the broken field, so each row
 * has to carry the same words the field carries. Matching on the tail of the path rather than the
 * whole of it is what lets one table cover `collectors.2.config.poll_interval_minutes` and
 * `collectors.4.config.poll_interval_minutes` without knowing how many collectors are on.
 */

import type { Messages } from '$lib/i18n/types';

export function fieldLabel(m: Messages, path: string): string {
  const label = m.field.label;
  const segments = path.split('.');
  const tail = segments[segments.length - 1];
  const pair = segments.slice(-2).join('.');

  switch (pair) {
    case 'researcher.name':
      return label.researcherName;
    case 'researcher.contact':
      return label.researcherContact;
    case 'consent.document_version':
      return label.consentDocumentVersion;
    case 'consent.summary':
      return label.consentSummary;
    case 'signer.key_id':
      return label.signerKeyId;
    case 'signer.public_key':
      return label.signerPublicKey;
    case 'export.researcher_key_id':
      return label.exportKeyId;
    case 'export.hpke_public_key':
      return label.exportPublicKey;
    case 'upload.endpoint':
      return label.endpoint;
    case 'upload.interval_minutes':
      return label.uploadInterval;
    case 'upload.allow_metered':
      return label.allowMetered;
    case 'storage.maximum_local_bytes':
      return label.storageQuota;
  }

  switch (tail) {
    case 'experiment_id':
      return label.experimentId;
    case 'configuration_id':
      return label.configurationId;
    case 'issued_at':
      return label.issuedAt;
    case 'expires_at':
      return label.expiresAt;
    // No `minimum_client_version`: it is pinned and the path is unreachable. If
    // a future rule resurrects it, the fallback at the end renders the path itself, which is what
    // that fallback is for.
    case 'title':
      return label.title;
    case 'purpose':
      return label.purpose;
    case 'duration_hours':
      return label.durationHours;
    case 'required':
      return label.required;
    case 'sampling_period_us':
      return label.samplingPeriod;
    case 'maximum_report_latency_us':
      return label.reportLatency;
    case 'change_threshold_millilux':
    case 'change_threshold_millimeters':
      return label.changeThreshold;
    case 'minimum_event_interval_ms':
      return label.minimumEventInterval;
    case 'include_bandwidth_estimates':
      return label.bandwidthEstimates;
    case 'transports':
      return label.transports;
    case 'poll_interval_minutes':
      return label.pollInterval;
    case 'interval_millis':
      return label.interval;
    case 'minimum_interval_millis':
      return label.fastestInterval;
    case 'maximum_batch_delay_millis':
      return label.batchDelay;
    case 'minimum_displacement_millimeters':
      return label.displacement;
    case 'priority':
      return label.priority;
    case 'trajectory_sampling_hz':
      return label.trajectoryRate;
    case 'collectors':
      return m.researcher.study.section.collectors.title;
  }

  if (segments[0] === 'interventions') return m.researcher.study.section.interventions.title;
  if (segments[0] === 'surveys') return m.intervention.survey;
  // The whole document, and anything a future rule invents: the path itself, never nothing.
  return path || m.researcher.sign.canonical;
}
