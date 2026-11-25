/**
 * The data a Space Template ships to a new Space (`SPACES-CONSOLIDATION-1` P3b): the repository keeps a Template's
 * configuration, not its generated corpus, so a script that lays a Space down from a Template takes the data from the
 * Template's folder when it still has some (a custom Template, a fixture) and otherwise has the Template's own generator
 * write it (`tools/seed-space-data.mjs`).
 */
import { cpSync, existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { GENERATORS, seed } from './seed-space-data.mjs';

/** Put the Template's data into {@code dataDir}: copy {@code <templatesDir>/<template>/data} if it exists, else generate it. */
export function seedData(template, dataDir, templatesDir) {
  const shipped = join(templatesDir, template, 'data');
  if (existsSync(shipped)) {
    mkdirSync(dataDir, { recursive: true });
    cpSync(shipped, dataDir, { recursive: true });
    return 'copied';
  }
  if (GENERATORS[template]) {
    seed(template, dataDir);
    return 'generated';
  }
  mkdirSync(dataDir, { recursive: true });
  return 'none';
}
