import { access, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const outputDir = join(projectRoot, 'android-app', 'app', 'build', 'outputs', 'apk', 'debug');
const gradleApk = join(outputDir, 'app-debug.apk');
const studyApk = join(outputDir, 'Study.apk');
const metadataPath = join(outputDir, 'output-metadata.json');

const metadata = JSON.parse(await readFile(metadataPath, 'utf8'));
if (metadata.elements?.length !== 1) {
  throw new Error('Expected one debug APK output.');
}
const outputName = metadata.elements[0].outputFile;
if (typeof outputName !== 'string' || basename(outputName) !== outputName || !/^[A-Za-z0-9._-]+.apk$/.test(outputName)) {
  throw new Error('Gradle returned an invalid APK output name.');
}
const metadataApk = join(outputDir, outputName);
try {
  await access(gradleApk);
} catch {
  try {
    await access(metadataApk);
  } catch {
    try {
      await access(studyApk);
    } catch {
      throw new Error('No debug APK output is available.');
    }
  }
}

if (await access(gradleApk).then(() => true, () => false)) {
  await rm(studyApk, { force: true });
  await rename(gradleApk, studyApk);
} else if (metadataApk !== studyApk && await access(metadataApk).then(() => true, () => false)) {
  await rm(studyApk, { force: true });
  await rename(metadataApk, studyApk);
}
await rm(gradleApk, { force: true });
for (const element of metadata.elements) {
  element.outputFile = 'Study.apk';
}
await writeFile(metadataPath, `${JSON.stringify(metadata, null, 2)}\n`);
