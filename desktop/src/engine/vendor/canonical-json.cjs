// Adapted from yuan69082-code/vio-live-frontend @ 1eaeef29c6e09156fa0350392f0acb95f9a17c96.
// Only module imports and local error types changed. See ../README.md.
const { createHash } = require('node:crypto');

const { ValidationError } = require('../errors.cjs');

function assertUnicodeScalarString(value) {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (index + 1 >= value.length || next < 0xdc00 || next > 0xdfff) {
        throw new ValidationError('RFC 8785 input contains an unpaired surrogate.');
      }
      index += 1;
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      throw new ValidationError('RFC 8785 input contains an unpaired surrogate.');
    }
  }
}

function canonicalizeValue(value, seen) {
  if (value === null) return 'null';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'string') {
    assertUnicodeScalarString(value);
    return JSON.stringify(value);
  }
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) {
      throw new ValidationError('RFC 8785 input numbers must be finite.');
    }
    return JSON.stringify(value);
  }
  if (typeof value !== 'object') {
    throw new ValidationError('Value cannot be canonicalized as RFC 8785 JSON.');
  }
  if (seen.has(value)) {
    throw new ValidationError('RFC 8785 input must not contain cycles.');
  }

  seen.add(value);
  try {
    if (Array.isArray(value)) {
      return `[${value.map((item) => canonicalizeValue(item, seen)).join(',')}]`;
    }

    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) {
      throw new ValidationError('RFC 8785 input must contain plain JSON objects.');
    }
    const keys = Object.keys(value).sort();
    const members = keys.map((key) => {
      assertUnicodeScalarString(key);
      return `${JSON.stringify(key)}:${canonicalizeValue(value[key], seen)}`;
    });
    return `{${members.join(',')}}`;
  } finally {
    seen.delete(value);
  }
}

function canonicalizeJson(value) {
  return Buffer.from(canonicalizeValue(value, new Set()), 'utf8');
}

function sha256Hash(value) {
  return `sha256:${createHash('sha256').update(value).digest('hex')}`;
}


module.exports = { canonicalizeJson, sha256Hash };
