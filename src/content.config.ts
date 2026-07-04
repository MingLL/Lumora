import { defineCollection } from 'astro:content';
import { glob } from 'astro/loaders';
import { z } from 'astro/zod';

const blog = defineCollection({
  loader: glob({ pattern: '**/*.{md,mdx}', base: './src/content/blog' }),
  schema: z.object({
    title: z.string(),
    description: z.string(),
    date: z.coerce.date(),
    category: z.enum(['关于我', '成长手记', '北京生活', '扫街周记', '城记远方']),
    tags: z.array(z.string()).default([]),
    city: z.string().optional().default(''),
    cover: z.string().optional().default(''),
    featured: z.boolean().default(false),
    series: z.string().optional().default('')
  })
});

export const collections = { blog };
