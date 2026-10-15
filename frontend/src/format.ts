const dateTimeFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' });
const numberFormat = new Intl.NumberFormat();

export function formatDateTime(isoTimestamp: string): string {
  const date = new Date(isoTimestamp);
  return Number.isNaN(date.getTime()) ? isoTimestamp : dateTimeFormat.format(date);
}

export function formatCount(value: number): string {
  return numberFormat.format(value);
}
