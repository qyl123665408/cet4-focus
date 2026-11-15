import fs from 'node:fs/promises';
import path from 'node:path';

const root = process.cwd();
const sourceData = path.join(root, 'data');
const destination = path.join(root, 'android-app', 'app', 'src', 'main', 'assets');

await fs.rm(destination, { recursive: true, force: true });
await fs.mkdir(path.join(destination, 'data', 'modules'), { recursive: true });
for (const file of ['version.json', 'questions.json', 'vocab.json', 'reading-sources.json']) {
  await fs.copyFile(path.join(sourceData, file), path.join(destination, 'data', file));
}
await fs.copyFile(path.join(sourceData, 'modules', 'index.json'), path.join(destination, 'data', 'modules', 'index.json'));
for (const entry of await fs.readdir(path.join(sourceData, 'modules'), { withFileTypes: true })) {
  if (entry.isFile() && entry.name.endsWith('.json') && entry.name !== 'index.json') {
    await fs.copyFile(path.join(sourceData, 'modules', entry.name), path.join(destination, 'data', 'modules', entry.name));
  }
}
console.log(`Synced native app content data to ${destination}`);
