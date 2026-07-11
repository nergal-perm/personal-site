import { defineCollection, z } from 'astro:content';
import { glob } from 'astro/loaders';

// A single "posts" collection. The schema carries the full editorial metadata
// the single-post layout renders in its rails (details, sources, related, etc.),
// so the page stays data-driven and the layout never hardcodes article content.
const posts = defineCollection({
  loader: glob({ pattern: '**/*.md', base: './src/content/posts' }),
  schema: z.object({
    title: z.string(),
    // Header line
    contentType: z.string().default('Essay'),
    topic: z.string(),
    publishDate: z.string(),
    updatedDate: z.string().optional(),
    readingTime: z.string(),
    abstract: z.string(),

    // ARTICLE DETAILS rail
    status: z.string().default('Published'),
    tags: z.array(z.string()).default([]),
    location: z.string(),
    wordCount: z.string(),
    revision: z.union([z.number(), z.string()]),

    // FIG. 1 process figure
    figure: z
      .object({
        label: z.string().default('FIG. 1'),
        title: z.string(),
        caption: z.string(),
        steps: z.array(
          z.object({
            n: z.number(),
            label: z.string(),
            icon: z.string(),
          }),
        ),
      })
      .optional(),

    // SOURCES rail
    sources: z
      .array(z.object({ n: z.number(), text: z.string() }))
      .default([]),

    // RELATED CONCEPTS map
    related: z
      .object({
        center: z.string(),
        nodes: z.array(z.string()),
      })
      .optional(),

    // BACKLINKS IN THE ATLAS band
    backlinks: z
      .array(z.object({ title: z.string(), sub: z.string() }))
      .default([]),
  }),
});

export const collections = { posts };
