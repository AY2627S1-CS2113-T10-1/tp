import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { after, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { ConsoleProcess, runCases, validateFixtures } from '../.agents/skills/test-ui/scripts/run-ui-tests.mjs';

const ROOT = fileURLToPath(new URL('../', import.meta.url));
const JAVA = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java';
const JAR = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/jar') : 'jar';
const JAR_FILE = path.join(ROOT, 'build/libs/scheduleflow.jar');
const transcript = [];
const record = text => transcript.push(text);
const execute = promisify(execFile);

after(async () => {
    const directory = path.join(ROOT, 'build/ui-transcripts');
    await mkdir(directory, { recursive: true });
    const file = path.join(directory,
        `release-${new Date().toISOString().replaceAll(':', '-')}-${process.pid}.txt`);
    await writeFile(file, transcript.join('\n') + '\n', 'utf8');
    console.log(`Release transcript: ${file}`);
});

test('the release JAR passes every maintained scenario with assertions enabled', async () => {
    const version = await execute(JAVA, ['-version']);
    assert.match(version.stderr, /version "25(?:[."])/, 'Release tests require Java 25');
    record(version.stderr.trim());
    const fixtures = JSON.parse(await readFile(path.join(ROOT, 'test/ui-scenarios.json'), 'utf8'));
    validateFixtures(fixtures);
    assert.ok(fixtures.cases.every(scenario => scenario.status === 'active'),
        'Release acceptance must not silently skip planned scenarios');
    record(`COMMAND ${JAVA} -ea -jar ${JAR_FILE}`);
    try {
        await runCases(fixtures.cases, JAVA, ['-ea', '-jar', JAR_FILE], record);
        record(`RESULT assertions-enabled JAR passed=${fixtures.cases.length} failed=0`);
    } catch (error) {
        record(error.stack);
        throw error;
    }
});

test('the normal distribution includes the release JAR and working assertion-enabled launchers', async () => {
    const archives = (await readdir(path.join(ROOT, 'build/distributions')))
        .filter(name => name.endsWith('.zip') && !name.endsWith('-shadow.zip'));
    assert.equal(archives.length, 1, 'Build exactly one normal distribution ZIP before testing');
    const temporaryRoot = path.resolve(tmpdir());
    const directory = await mkdtemp(path.join(temporaryRoot, 'scheduleflow-release-'));
    assert.ok(directory.startsWith(temporaryRoot + path.sep), 'Keep extraction and cleanup inside temporary storage');
    record(`DISTRIBUTION ${archives[0]}\nDIRECTORY ${directory}`);
    try {
        const archive = path.join(ROOT, 'build/distributions', archives[0]);
        await execute(JAR, ['--extract', '--file', archive], { cwd: directory, timeout: 10000 });
        const roots = await readdir(directory);
        assert.equal(roots.length, 1);
        const application = path.join(directory, roots[0]);
        assert.deepEqual(await readFile(path.join(application, 'lib/scheduleflow.jar')), await readFile(JAR_FILE),
            'The distribution must include the exact standalone release JAR');
        const scripts = await readdir(path.join(application, 'bin'));
        const windowsScript = scripts.find(name => name.endsWith('.bat'));
        assert.ok(windowsScript, 'The distribution must include a Windows launcher');
        const unixScript = windowsScript.slice(0, -4);
        for (const script of [windowsScript, unixScript]) {
            const text = await readFile(path.join(application, 'bin', script), 'utf8');
            assert.match(text, /DEFAULT_JVM_OPTS=[^\r\n]*-ea/, `${script} must enable assertions by default`);
            record(`VERIFIED ${script}: assertions enabled by default`);
        }
        const runtime = path.join(directory, 'runtime');
        await mkdir(runtime);
        const isWindows = process.platform === 'win32';
        const launcher = path.join(application, 'bin', isWindows ? windowsScript : unixScript);
        const child = new ConsoleProcess(isWindows ? 'cmd.exe' : 'sh',
            isWindows ? ['/d', '/c', launcher] : [launcher], runtime, record);
        try {
            await child.frame('');
            child.send('exit');
            await child.frame('Goodbye for now!\n', 0);
        } finally {
            await child.stop();
        }
        assert.deepEqual(await readdir(runtime), [], 'Read-only launch must not create saved data');
        record('RESULT normal distribution startup and exit passed');
    } catch (error) {
        record(error.stack);
        throw error;
    } finally {
        await rm(directory, { recursive: true, force: true });
    }
});
