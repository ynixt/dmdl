import { execFileSync } from 'node:child_process';
import { closeSync, openSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));

function git(...args) {
  return execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: 'pipe' }).trim();
}

function replaceOnce(content, pattern, replacement, path) {
  if ([...content.matchAll(pattern)].length !== 1) {
    throw new Error(`Expected exactly one release version in ${path}.`);
  }
  return content.replace(pattern, replacement);
}

function updateJson(content, update) {
  const data = JSON.parse(content);
  update(data);
  const newline = content.includes('\r\n') ? '\r\n' : '\n';
  return `${JSON.stringify(data, null, 2)}\n`.replaceAll('\n', newline);
}

function release() {
  const version = (process.argv[2] ?? '').replace(/^v/, '');
  if (process.argv.length !== 3 || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(version)) {
    throw new Error('Usage: make release 0.0.8 (MAJOR.MINOR.PATCH).');
  }

  const tag = `v${version}`;
  const lockPath = resolve(root, git('rev-parse', '--git-path', 'dmdl-release.lock'));
  const lock = openSync(lockPath, 'wx');
  const files = [];
  let commitCreated = false;

  try {
    if (git('tag', '--list', tag)) {
      throw new Error(`Tag ${tag} already exists.`);
    }
    git('rev-parse', '--verify', 'HEAD');
    git('var', 'GIT_AUTHOR_IDENT');
    git('var', 'GIT_COMMITTER_IDENT');

    const updates = new Map([
      ['frontend/package.json', (content) => updateJson(content, (data) => {
        data.version = version;
      })],
      ['frontend/package-lock.json', (content) => updateJson(content, (data) => {
        data.version = version;
        data.packages[''].version = version;
      })],
      ['backend/build.gradle.kts', (content) => replaceOnce(
        content, /^version = "[^"]+"$/gm, `version = "${version}"`, 'backend/build.gradle.kts',
      )],
      ['README.md', (content) => {
        const imageUpdated = replaceOnce(
          content, /ghcr\.io\/ynixt\/dmdl:[^`\s]+/g, `ghcr.io/ynixt/dmdl:${version}`, 'README.md',
        );
        return replaceOnce(
          imageUpdated, /release tag \(such as `v[^`]+`\)/g,
          `release tag (such as \`v${version}\`)`, 'README.md',
        );
      }],
    ]);

    git('ls-files', '--error-unmatch', '--', ...updates.keys());
    for (const [path, update] of updates) {
      const original = readFileSync(resolve(root, path), 'utf8');
      files.push({ path, original, updated: update(original) });
    }
    for (const file of files) {
      writeFileSync(resolve(root, file.path), file.updated);
    }

    const paths = files.map((file) => file.path);
    if (git('diff', 'HEAD', '--', ...paths)) {
      console.log(git('commit', '--only', '-m', tag, '--', ...paths));
      commitCreated = true;
    }
    const commit = git('rev-parse', 'HEAD');
    git('tag', tag, commit);
    console.log(`Created ${tag} at ${commit}.`);
  } catch (error) {
    if (commitCreated) {
      console.error(`The release commit was created. Retry with make release ${version} to create the tag.`);
    } else {
      for (const file of files) {
        writeFileSync(resolve(root, file.path), file.original);
      }
    }
    throw error;
  } finally {
    closeSync(lock);
    unlinkSync(lockPath);
  }
}

try {
  release();
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
