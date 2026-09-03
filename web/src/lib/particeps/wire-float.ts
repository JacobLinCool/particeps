const PROTOCOL_DECIMAL_FLOAT = /^[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?)$/;

/** True when a value uses the Protocol v1 event-wire decimal float grammar. */
export function isProtocolDecimalFloat(value: string): boolean {
  return PROTOCOL_DECIMAL_FLOAT.test(value);
}

/**
 * Format one finite IEEE-754 value with Java's `Double.toString` spelling.
 *
 * Signed predicate literals use this single spelling. Event wire values deliberately accept the
 * broader Protocol decimal grammar and are normalized only after decoding.
 */
export function javaDoubleString(value: number): string {
  if (!Number.isFinite(value)) throw new Error('finite_double_required');
  if (Object.is(value, -0)) return '-0.0';
  if (value === 0) return '0.0';
  const negative = value < 0;
  const raw = Math.abs(value).toString().toLowerCase();
  const [mantissa, exponentText] = raw.split('e');
  const [whole, fraction = ''] = mantissa.split('.');
  let digits: string;
  let decimalExponent: number;
  if (exponentText !== undefined) {
    digits = `${whole}${fraction}`.replace(/^0+/, '').replace(/0+$/, '') || '0';
    decimalExponent = Number(exponentText) + whole.replace(/^0+/, '').length - 1;
  } else if (whole.replace(/^0+/, '')) {
    const significantWhole = whole.replace(/^0+/, '');
    digits = `${significantWhole}${fraction}`.replace(/0+$/, '') || '0';
    decimalExponent = significantWhole.length - 1;
  } else {
    const first = [...fraction].findIndex((character) => character !== '0');
    digits = fraction.slice(first).replace(/0+$/, '') || '0';
    decimalExponent = -first - 1;
  }
  const sign = negative ? '-' : '';
  if (decimalExponent >= -3 && decimalExponent < 7) {
    const point = decimalExponent + 1;
    if (point <= 0) return `${sign}0.${'0'.repeat(-point)}${digits}`;
    if (point >= digits.length) return `${sign}${digits}${'0'.repeat(point - digits.length)}.0`;
    return `${sign}${digits.slice(0, point)}.${digits.slice(point)}`;
  }
  return `${sign}${digits[0]}.${digits.slice(1) || '0'}E${decimalExponent}`;
}
