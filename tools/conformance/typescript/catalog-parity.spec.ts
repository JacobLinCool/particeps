import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { canonicalBytes, canonicalConfigurationBytes, configurationValue } from '../../../web/src/lib/particeps/canonical';
import { defaultCollector, validate } from '../../../web/src/lib/particeps/schema';
import { COLLECTOR_ORDER, type CollectorConfig, type CollectorId } from '../../../web/src/lib/particeps/types';
import { parseConfiguration } from '../../../web/src/routes/researcher/parse';
import { validConfiguration } from '../../../web/tests/fixture';

type ConfigurationField =
  | { type: 'integer'; minimum: number; maximum: number; maximum_field?: string }
  | { type: 'boolean' }
  | { type: 'enum'; enum: string[] }
  | { type: 'enum_array'; items_enum: string[]; minimum_items: number; maximum_items: number };

type CatalogCollector = {
  id: string;
  platforms: string[];
  selectable: boolean;
  implementation: { status: string };
  configuration: { fields: Record<string, ConfigurationField>; required: string[] } | null;
};

const catalog = JSON.parse(
  readFileSync(new URL('../../../protocol/v1/collector-catalog.json', import.meta.url), 'utf8')
) as { collectors: CatalogCollector[] };

const implemented = catalog.collectors.filter((collector) =>
  collector.implementation.status === 'implemented' &&
  collector.selectable &&
  collector.platforms.includes('android')
);

const configRecord = (collector: CollectorConfig): Record<string, unknown> =>
  collector.config as Record<string, unknown>;

function withCollector(collector: CollectorConfig) {
  return validConfiguration({ collectors: [collector] });
}

function atIntegerBoundary(
  collector: CollectorConfig,
  field: string,
  value: number
): CollectorConfig {
  const copy = structuredClone(collector);
  configRecord(copy)[field] = value;
  if (copy.id === 'location.v1') {
    if (field === 'interval_millis' && value < copy.config.minimum_interval_millis) {
      copy.config.minimum_interval_millis = value;
    }
    if (field === 'minimum_interval_millis' && value > copy.config.interval_millis) {
      copy.config.interval_millis = value;
    }
  }
  return copy;
}

function collectorIssues(collector: CollectorConfig) {
  return validate(withCollector(collector)).filter((issue) => issue.path.startsWith('collectors.0'));
}

function rawConfiguration(collector: CollectorConfig): Record<string, unknown> {
  return configurationValue(withCollector(collector)) as Record<string, unknown>;
}

describe('collector catalog Web projection', () => {
  it('selects exactly the catalog\'s implemented Android collectors', () => {
    expect(new Set(COLLECTOR_ORDER)).toEqual(new Set(implemented.map((collector) => collector.id)));
    expect(COLLECTOR_ORDER).toHaveLength(new Set(COLLECTOR_ORDER).size);
  });

  for (const definition of implemented) {
    const id = definition.id as CollectorId;
    const schema = definition.configuration!;

    it(`${id} default and closed-world shape match the catalog`, () => {
      const collector = defaultCollector(id);
      expect(collector.id).toBe(id);
      expect(new Set(Object.keys(configRecord(collector)))).toEqual(new Set(Object.keys(schema.fields)));
      expect(new Set(schema.required)).toEqual(new Set(Object.keys(schema.fields)));
      expect(collectorIssues(collector)).toEqual([]);
      expect(parseConfiguration(canonicalConfigurationBytes(withCollector(collector))).collectors)
        .toEqual([collector]);

      const unknown = rawConfiguration(collector);
      const unknownConfig = ((unknown.collectors as Array<Record<string, unknown>>)[0]
        .config as Record<string, unknown>);
      unknownConfig.unknown_catalog_field = true;
      expect(() => parseConfiguration(canonicalBytes(unknown))).toThrow('parse_keys');

      const firstField = Object.keys(schema.fields)[0];
      if (firstField) {
        const missing = rawConfiguration(collector);
        const missingConfig = ((missing.collectors as Array<Record<string, unknown>>)[0]
          .config as Record<string, unknown>);
        delete missingConfig[firstField];
        expect(() => parseConfiguration(canonicalBytes(missing))).toThrow('parse_keys');
      }
    });

    for (const [fieldName, field] of Object.entries(schema.fields)) {
      if (field.type === 'integer') {
        it(`${id}.${fieldName} enforces the catalog's inclusive integer bounds`, () => {
          for (const boundary of [field.minimum, field.maximum]) {
            const collector = atIntegerBoundary(defaultCollector(id), fieldName, boundary);
            expect(collectorIssues(collector)).toEqual([]);
            expect(() => parseConfiguration(canonicalConfigurationBytes(withCollector(collector))))
              .not.toThrow();
          }
          for (const outside of [field.minimum - 1, field.maximum + 1]) {
            const issues = collectorIssues(atIntegerBoundary(defaultCollector(id), fieldName, outside));
            expect(issues).toContainEqual(expect.objectContaining({
              path: `collectors.0.config.${fieldName}`,
              code: 'number_range',
              bounds: { min: field.minimum, max: field.maximum }
            }));
          }
        });
        if (field.maximum_field) {
          it(`${id}.${fieldName} enforces the catalog's cross-field ceiling`, () => {
            const referenced = schema.fields[field.maximum_field];
            expect(referenced?.type).toBe('integer');
            if (!referenced || referenced.type !== 'integer') return;
            const collector = defaultCollector(id);
            configRecord(collector)[field.maximum_field!] = referenced.minimum;
            configRecord(collector)[fieldName] = Math.max(field.minimum, referenced.minimum + 1);
            const issues = collectorIssues(collector);
            expect(issues).toContainEqual(expect.objectContaining({
              path: `collectors.0.config.${fieldName}`
            }));
            expect(() => parseConfiguration(canonicalConfigurationBytes(withCollector(collector))))
              .toThrow('parse_invalid');
          });
        }
      } else if (field.type === 'boolean') {
        it(`${id}.${fieldName} accepts only a JSON boolean`, () => {
          const raw = rawConfiguration(defaultCollector(id));
          const config = ((raw.collectors as Array<Record<string, unknown>>)[0]
            .config as Record<string, unknown>);
          config[fieldName] = 'false';
          expect(() => parseConfiguration(canonicalBytes(raw))).toThrow('parse_boolean');
        });
      } else if (field.type === 'enum') {
        it(`${id}.${fieldName} accepts exactly the catalog enum`, () => {
          expect(field.enum).toContain(configRecord(defaultCollector(id))[fieldName]);
          const raw = rawConfiguration(defaultCollector(id));
          const config = ((raw.collectors as Array<Record<string, unknown>>)[0]
            .config as Record<string, unknown>);
          config[fieldName] = 'NOT_IN_CATALOG';
          expect(() => parseConfiguration(canonicalBytes(raw))).toThrow('parse_collector');
        });
      } else {
        it(`${id}.${fieldName} accepts only catalog enum members`, () => {
          const actual = configRecord(defaultCollector(id))[fieldName] as string[];
          expect(actual.length).toBeGreaterThanOrEqual(field.minimum_items);
          expect(actual.length).toBeLessThanOrEqual(field.maximum_items);
          expect(actual.every((value) => field.items_enum.includes(value))).toBe(true);
          const raw = rawConfiguration(defaultCollector(id));
          const config = ((raw.collectors as Array<Record<string, unknown>>)[0]
            .config as Record<string, unknown>);
          config[fieldName] = ['not-in-catalog'];
          expect(() => parseConfiguration(canonicalBytes(raw))).toThrow('parse_collector');
        });
      }
    }
  }
});
