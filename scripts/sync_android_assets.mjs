import fs from 'node:fs/promises';
import path from 'node:path';

const root = process.cwd();
const sourceFiles = ['index.html', 'app.js', 'styles.css', 'manifest.json', 'service-worker.js', 'favicon.svg'];
const sourceData = path.join(root, 'data');
const destination = path.join(root, 'android-app', 'app', 'src', 'main', 'assets');

await fs.rm(destination, { recursive: true, force: true });
await fs.mkdir(destination, { recursive: true });
for (const file of sourceFiles) await fs.copyFile(path.join(root, file), path.join(destination, file));
await fs.cp(sourceData, path.join(destination, 'data'), { recursive: true });
console.log(`Synced web app assets to ${destination}`);
