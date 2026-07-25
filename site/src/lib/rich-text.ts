export type RichTextToken =
  | { kind: 'text'; value: string }
  | { kind: 'reference'; target: string };

export type RichTextValue = string | RichTextToken[];

export function isRichTextValue(value: unknown): value is RichTextToken[] {
  return Array.isArray(value) && value.every((token) => {
    if (!token || typeof token !== 'object') return false;
    const keys = Object.keys(token);
    if (token.kind === 'text') {
      return keys.length === 2 && keys.includes('value') && typeof token.value === 'string';
    }
    return token.kind === 'reference' &&
      keys.length === 2 && keys.includes('target') &&
      typeof token.target === 'string' && token.target.length > 0;
  });
}

export function plainText(value: RichTextValue | undefined): string {
  if (typeof value === 'string') return value;
  if (!isRichTextValue(value)) return '';
  return value.map((token) => token.kind === 'text' ? token.value : '').join('');
}
