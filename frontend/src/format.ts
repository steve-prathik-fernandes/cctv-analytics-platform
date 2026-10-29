/** "ZONE_ENTRY" → "Zone entry". */
export function label(type: string): string {
  return type.charAt(0) + type.slice(1).toLowerCase().replaceAll('_', ' ')
}
