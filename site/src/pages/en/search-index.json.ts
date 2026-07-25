import type { APIRoute } from 'astro';
import { getSearchableEntries, toSearchRow } from '../../lib/registry';

export const GET: APIRoute = async () => {
  const entries = await getSearchableEntries('en');
  const rows = entries.map(toSearchRow);
  return new Response(JSON.stringify(rows), {
    headers: {
      'Content-Type': 'application/json',
    },
  });
};
