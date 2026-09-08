#!/usr/bin/env python3
"""Exercise sync safety with real temporary Git repos and fake build/GitHub tools.

No network, real builds, or GitHub mutations are performed.
"""
import errno
import os
from pathlib import Path
import pty
import shutil
import subprocess
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[1]


class SyncTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="scrcaster-sync-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.work = self.root / "checkout with spaces"
        self.work.mkdir()
        self.origin = self.root / "origin.git"
        self.upstream = self.root / "upstream.git"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.calls = self.root / "calls.log"
        self.env = os.environ.copy()
        for key in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR",
                    "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT"):
            self.env.pop(key, None)
        self.env.update({
            "PATH": f"{self.bin}:{self.env['PATH']}",
            "GIT_TERMINAL_PROMPT": "0",
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": "/dev/null",
            "GIT_EDITOR": "true",
            "SYNC_TEST_CALLS": str(self.calls),
        })
        self.git("init", "-q", "--initial-branch=main")
        self.identity(self.work)
        shutil.copyfile(PROJECT / "sync-upstream.sh", self.work / "sync-upstream.sh")
        (self.work / "scripts").mkdir()
        (self.work / "scripts/prepare-miuix.sh").write_text("#!/bin/bash\nexit 0\n")
        (self.work / ".gitignore").write_text("build/\n")
        (self.work / "upstream.txt").write_text("base\n")
        (self.work / "build.sh").write_text(
            '#!/bin/bash\necho "android $*" >> "$SYNC_TEST_CALLS"\n'
            'exit "${SYNC_TEST_FAIL_ANDROID:-0}"\n'
        )
        (self.work / "gradlew").write_text(
            '#!/bin/bash\necho "gradle $*" >> "$SYNC_TEST_CALLS"\n'
            'exit "${SYNC_TEST_FAIL_LINUX:-0}"\n'
        )
        self.executable("xvfb-run", '#!/bin/bash\nshift\nexec "$@"\n')
        self.executable("gh", '''#!/bin/bash
echo "gh $*" >> "$SYNC_TEST_CALLS"
case "$1 $2" in
    'auth status') exit 0 ;;
    'pr list') printf '%s' "${SYNC_TEST_EXISTING_PR:-}" ;;
    'pr create') echo 'https://github.com/example/ScrCaster/pull/1' ;;
    *) exit 99 ;;
esac
''')
        self.commit("Fixture baseline")
        self.git("clone", "-q", "--bare", str(self.work), str(self.origin))
        self.git("clone", "-q", "--bare", str(self.work), str(self.upstream))
        self.git("remote", "add", "origin", "https://github.com/example/ScrCaster.git")
        self.git("remote", "add", "upstream", "https://github.com/Miuzarte/ScrcpyForAndroid.git")
        self.git("config", f"url.{self.origin}.insteadOf", "https://github.com/example/ScrCaster.git")
        self.git("config", f"url.{self.upstream}.insteadOf", "https://github.com/Miuzarte/ScrcpyForAndroid.git")
        self.git("fetch", "-q", "origin")

    def executable(self, name, text):
        path = self.bin / name
        path.write_text(text)
        path.chmod(0o755)

    def git(self, *args, cwd=None, check=True):
        return subprocess.run(["git", *args], cwd=cwd or self.work, env=self.env,
                              text=True, capture_output=True, check=check).stdout.strip()

    def identity(self, path):
        self.git("config", "user.name", "Sync Test", cwd=path)
        self.git("config", "user.email", "sync-test@example.invalid", cwd=path)
        self.git("config", "commit.gpgSign", "false", cwd=path)

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-qm", message)
        return self.git("rev-parse", "HEAD")

    def incoming(self, *, remote=None, name="upstream.txt", text="upstream update\n"):
        remote = remote or self.upstream
        edit = self.root / f"editor-{len(list(self.root.glob('editor-*')))}"
        self.git("clone", "-q", str(remote), str(edit))
        self.identity(edit)
        (edit / name).write_text(text)
        self.git("add", "-A", cwd=edit)
        self.git("commit", "-qm", "Remote update", cwd=edit)
        self.git("push", "-q", "origin", "main", cwd=edit)
        return self.git("rev-parse", "HEAD", cwd=edit)

    def run_sync(self, *options, ok=True, extra_env=None):
        result = subprocess.run(
            ["bash", str(self.work / "sync-upstream.sh"), *options], cwd=self.root,
            env={**self.env, **(extra_env or {})}, text=True, capture_output=True, timeout=30,
        )
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode == 0, ok, output)
        self.assertFalse((self.work / ".git/scrcaster-upstream-sync.lock").exists())
        return output

    def test_help_and_noninteractive_confirmation(self):
        self.assertIn("--continue", self.run_sync("--help"))
        self.assertIn("No interactive terminal", self.run_sync(ok=False))

    def run_interactive(self, answers):
        master, slave = pty.openpty()
        process = subprocess.Popen(
            ["bash", str(self.work / "sync-upstream.sh")], cwd=self.root,
            env=self.env, stdin=slave, stdout=slave, stderr=slave,
        )
        os.close(slave)
        try:
            os.write(master, answers.encode())
            # The timeout prevents a broken prompt from hanging tests.
            # Output is small enough for the PTY buffer in these fixture cases.
            process.wait(timeout=30)
            chunks = []
            while True:
                try:
                    chunk = os.read(master, 65536)
                    if not chunk:
                        break
                    chunks.append(chunk)
                except OSError as exc:
                    if exc.errno != errno.EIO:
                        raise
                    break
            output = b"".join(chunks).decode()
            self.assertEqual(process.returncode, 0, output)
            return output
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            os.close(master)

    def test_interactive_decline_changes_nothing(self):
        original = self.git("rev-parse", "HEAD")
        self.incoming()
        self.run_interactive("n\n")
        self.assertEqual(self.git("branch", "--show-current"), "main")
        self.assertEqual(self.git("rev-parse", "HEAD"), original)
        self.assertFalse(self.calls.exists())

    def test_interactive_default_keeps_local(self):
        self.incoming()
        self.assertIn("Kept locally", self.run_interactive("\n\n"))
        self.assertNotIn("gh ", self.calls.read_text())

    def test_interactive_publish_choice(self):
        self.incoming()
        self.assertIn("Create a merge commit", self.run_interactive("y\n2\n"))
        self.assertIn("gh pr create --repo example/ScrCaster", self.calls.read_text())

    def test_missing_upstream_remote_is_configured(self):
        self.git("remote", "remove", "upstream")
        self.run_sync("--yes")
        self.assertEqual(self.git("config", "--get", "remote.upstream.pushurl"), "DISABLED")

    def test_up_to_date_does_not_create_branch_or_run_checks(self):
        self.assertIn("Already up to date", self.run_sync("--yes"))
        self.assertEqual(self.git("branch", "--show-current"), "main")
        self.assertFalse(self.calls.exists())

    def test_merge_preserves_history_and_main_without_publishing(self):
        original = self.git("rev-parse", "main")
        incoming = self.incoming()
        self.run_sync("--yes")
        self.assertTrue(self.git("branch", "--show-current").startswith("codex/sync-upstream-"))
        self.assertEqual(self.git("rev-parse", "main"), original)
        self.assertEqual(self.git("rev-parse", "main", cwd=self.origin), original)
        self.git("merge-base", "--is-ancestor", incoming, "HEAD")
        self.assertEqual(len(self.git("show", "-s", "--format=%P", "HEAD").split()), 2)
        self.assertIn("guiSmoke", self.calls.read_text())
        self.assertNotIn("gh ", self.calls.read_text())

    def test_dirty_parent_is_preserved(self):
        (self.work / "unsaved.txt").write_text("keep me\n")
        original = self.git("rev-parse", "HEAD")
        self.run_sync("--yes", ok=False)
        self.assertEqual((self.work / "unsaved.txt").read_text(), "keep me\n")
        self.assertEqual(self.git("rev-parse", "HEAD"), original)

    def test_wrong_branch_and_wrong_upstream_are_rejected(self):
        self.git("switch", "-qc", "feature/work")
        self.assertIn("Start on main", self.run_sync("--yes", ok=False))
        self.git("switch", "-q", "main")
        self.git("remote", "set-url", "upstream", "https://github.com/other/repo.git")
        self.assertIn("upstream does not point", self.run_sync("--yes", ok=False))

    def test_upstream_as_origin_push_target_is_rejected(self):
        self.git("remote", "set-url", "--push", "origin", "https://github.com/miuzarte/scrcpyforandroid.git")
        self.assertIn("push URL points to upstream", self.run_sync("--yes", "--publish", ok=False))

    def test_divergent_main_is_not_reset(self):
        self.incoming(remote=self.origin, name="fork.txt")
        (self.work / "local.txt").write_text("local work\n")
        original = self.commit("Local change")
        self.assertIn("have diverged", self.run_sync("--yes", ok=False))
        self.assertEqual(self.git("rev-parse", "main"), original)

    def test_main_fast_forwards_only_from_origin(self):
        origin_head = self.incoming(remote=self.origin, name="fork.txt")
        self.assertIn("Already up to date", self.run_sync("--yes"))
        self.assertEqual(self.git("rev-parse", "main"), origin_head)
        self.assertFalse(self.calls.exists())

    def test_existing_branch_gets_unique_suffix(self):
        today = subprocess.check_output(["date", "+%Y-%m-%d"], text=True).strip()
        branch = f"codex/sync-upstream-{today}"
        self.git("branch", branch)
        self.incoming()
        self.run_sync("--yes")
        self.assertEqual(self.git("branch", "--show-current"), branch + "-2")

    def conflict(self):
        self.incoming(text="their version\n")
        (self.work / "upstream.txt").write_text("our version\n")
        original = self.commit("Local conflicting change")
        output = self.run_sync("--yes", ok=False)
        self.assertIn("Upstream merge stopped", output)
        self.assertEqual(self.git("rev-parse", "main"), original)
        self.assertFalse(self.calls.exists())

    def test_conflict_can_be_resolved_and_resumed(self):
        self.conflict()
        self.assertIn("unfinished", self.run_sync("--continue", "--yes", ok=False))
        (self.work / "upstream.txt").write_text("combined version\n")
        self.commit("Resolve upstream merge")
        self.run_sync("--continue", "--yes")
        self.assertIn("guiSmoke", self.calls.read_text())

    def test_aborted_merge_cannot_be_published_as_verified(self):
        self.conflict()
        self.git("merge", "--abort")
        self.assertIn("not complete", self.run_sync("--continue", "--yes", "--publish", ok=False))
        self.assertFalse(self.calls.exists())

    def test_failed_build_can_resume_pinned_upstream_snapshot(self):
        self.incoming()
        self.assertIn("Android APKs and unit tests failed", self.run_sync(
            "--yes", "--publish", ok=False, extra_env={"SYNC_TEST_FAIL_ANDROID": "1"}))
        self.assertNotIn("gh ", self.calls.read_text())
        merged_head = self.git("rev-parse", "HEAD")
        self.incoming(text="newer upstream update\n")
        self.run_sync("--continue", "--yes")
        self.assertEqual(self.git("rev-parse", "HEAD"), merged_head)

    def test_publish_targets_only_fork_sync_branch_and_reuses_pr(self):
        original = self.git("rev-parse", "main", cwd=self.origin)
        self.incoming()
        self.run_sync("--yes", "--publish", "--skip-gui")
        branch = self.git("branch", "--show-current")
        self.assertEqual(self.git("rev-parse", branch, cwd=self.origin), self.git("rev-parse", "HEAD"))
        self.assertEqual(self.git("rev-parse", "main", cwd=self.origin), original)
        calls = self.calls.read_text()
        self.assertIn("pr create --repo example/ScrCaster --base main", calls)
        self.assertNotIn("guiSmoke", calls)
        body = next((self.work / "build/upstream-sync").glob("*/pr-body.md")).read_text()
        self.assertIn("SKIPPED", body)
        self.run_sync("--continue", "--yes", "--publish", extra_env={
            "SYNC_TEST_EXISTING_PR": "https://github.com/example/ScrCaster/pull/1"})
        self.assertEqual(self.calls.read_text().count("gh pr create"), 1)

    def add_real_miuix(self):
        miuix = self.root / "miuix-source"
        miuix.mkdir()
        self.git("init", "-q", "--initial-branch=main", cwd=miuix)
        self.identity(miuix)
        (miuix / "settings.gradle.kts").write_text("// original setting\n")
        self.git("add", ".", cwd=miuix)
        self.git("commit", "-qm", "Miuix fixture", cwd=miuix)
        self.git("-c", "protocol.file.allow=always", "submodule", "add", "-q", str(miuix), "submodule/miuix")
        sub = self.work / "submodule/miuix"
        (sub / "settings.gradle.kts").write_text("// patched setting\n")
        patch = self.git("diff", "--binary", cwd=sub) + "\n"
        patch_dir = self.work / "patches/miuix"
        patch_dir.mkdir(parents=True)
        patch_file = patch_dir / "build-compatibility.patch"
        patch_file.write_text(patch)
        self.git("apply", "--reverse", str(patch_file), cwd=sub)
        shutil.copyfile(PROJECT / "scripts/prepare-miuix.sh", self.work / "scripts/prepare-miuix.sh")
        self.commit("Use real patch helper")
        return sub

    def test_recorded_miuix_patch_is_reapplied(self):
        sub = self.add_real_miuix()
        self.incoming()
        self.run_sync("--yes")
        self.assertEqual((sub / "settings.gradle.kts").read_text(), "// patched setting\n")

    def test_extra_submodule_edits_are_preserved_and_patch_restored(self):
        sub = self.add_real_miuix()
        (sub / "settings.gradle.kts").write_text("// patched setting\n")
        (sub / "unsaved.txt").write_text("keep submodule work\n")
        self.incoming()
        self.assertIn("Submodule changes remain", self.run_sync("--yes", ok=False))
        self.assertEqual((sub / "settings.gradle.kts").read_text(), "// patched setting\n")
        self.assertEqual((sub / "unsaved.txt").read_text(), "keep submodule work\n")
        self.assertEqual(self.git("branch", "--show-current"), "main")


if __name__ == "__main__":
    unittest.main(verbosity=2)
