export function parseInteger(text, { min = 0, max, empty = null } = {}) {
  const value = String(text ?? "").trim();
  if (!value) return empty;
  if (!/^\d+$/.test(value) || value.length > 20) return null;
  const number = Number(value);
  return Number.isSafeInteger(number) && number >= min && number <= max ? number : null;
}

export function parseDecimal(text, { max, scale, signed = false, nonzero = false, empty = null }) {
  const value = String(text ?? "").trim();
  if (!value) return empty;
  if (value.length > 32 || !(signed ? /^-?\d+(?:\.\d+)?$/ : /^\d+(?:\.\d+)?$/).test(value)) return null;
  const negative = value.startsWith("-");
  const [whole, fraction = ""] = value.replace(/^-/, "").split(".");
  if (fraction.length > scale) return null;
  const units = BigInt(whole) * (10n ** BigInt(scale)) + BigInt(fraction.padEnd(scale, "0") || "0");
  if (units > BigInt(max) * (10n ** BigInt(scale)) || (nonzero && units === 0n)) return null;
  return `${negative && units !== 0n ? "-" : ""}${BigInt(whole)}${fraction ? "." + fraction : ""}`;
}
