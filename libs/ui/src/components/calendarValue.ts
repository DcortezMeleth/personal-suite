import type { CalendarProps } from "react-calendar";

/**
 * What react-calendar passes to onChange.
 *
 * Derived from the public CalendarProps rather than deep-imported: the Value
 * type itself is not exported from the package root, and reaching into
 * react-calendar/dist/shared/types.js would bind us to its file layout.
 */
export type CalendarValue = Parameters<NonNullable<CalendarProps["onChange"]>>[0];

/**
 * The single date a calendar click selected, or null if it did not select one.
 *
 * react-calendar's value is `Date | null | [Date | null, Date | null]`, because
 * the same callback serves range selection. Both pickers here are single-date,
 * so the previous code asserted `value as Date` — which is a lie in two
 * directions: a deselect passes null, and turning on selectRange would start
 * passing a tuple, at which point formatting it would call getFullYear on an
 * array and the picker would crash rather than fail to compile.
 *
 * instanceof is a real check rather than an assertion, so a shape we did not
 * expect declines to select instead of throwing.
 */
export function singleDate(value: CalendarValue): Date | null {
  const picked = Array.isArray(value) ? value[0] : value;
  return picked instanceof Date ? picked : null;
}
