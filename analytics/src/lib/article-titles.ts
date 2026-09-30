import { readdir, readFile } from 'node:fs/promises';
import { resolve, relative, sep } from 'node:path';
import { parse } from 'yaml';

// Optional build-time metadata; the dashboard works without the blog checkout.
// No article body or image is shipped with the analytics site.
export async function loadArticleTitles(): Promise<Record<string, string>> {
  const directory = process.env.CONTENT_DIR
    ? resolve(process.env.CONTENT_DIR)
    : resolve(process.cwd(), '../content/blog');
  const titles: Record<string, string> = {};
  async function scan(folder: string): Promise<void> {
    for (const entry of await readdir(folder, { withFileTypes: true })) {
      const file = resolve(folder, entry.name);
      if (entry.isDirectory()) { await scan(file); continue; }
      if (!entry.isFile() || !/\.mdx?$/.test(entry.name)) continue;
      const body = await readFile(file, 'utf8');
      const frontmatter = body.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/)?.[1];
      if (!frontmatter) continue;
      const data = parse(frontmatter);
      if (typeof data?.title === 'string' && data.draft !== true) {
        const slug = relative(directory, file).split(sep).join('/').replace(/\.mdx?$/, '');
        titles[`/posts/${slug}`] = data.title;
      }
    }
  }
  try { await scan(directory); }
  catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
    console.warn('Article content directory unavailable; analytics will display article paths.');
  }
  return titles;
}
