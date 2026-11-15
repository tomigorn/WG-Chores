import datetime as dt
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import update_deps as u  # noqa: E402


def d(s):
    return dt.datetime.fromisoformat(s).replace(tzinfo=dt.timezone.utc)


CUTOFF = d("2026-11-12T00:00:00")


class Versions(unittest.TestCase):
    def test_parse_stable_only(self):
        self.assertEqual(u.parse_version("1.2.3"), (1, 2, 3))
        self.assertEqual(u.parse_version("v4.0"), (4, 0))
        self.assertIsNone(u.parse_version("1.2.3-beta.1"))
        self.assertIsNone(u.parse_version("1.2.3+build"))

    def test_compat_key_follows_cargo_semver(self):
        self.assertEqual(u.compat_key("1.4.2"), (1,))
        self.assertEqual(u.compat_key("0.8.3"), (0, 8))
        self.assertEqual(u.compat_key("0.0.3"), (0, 0, 3))
        self.assertEqual(u.compat_key("0.8"), (0, 8))

    def test_maven_stable(self):
        for v in ["2.0.0-alpha01", "1.9.0-rc02", "2.4.0-Beta1", "1.0-M3", "3.0-dev", "1.0.0-SNAPSHOT", "9.6.0-eap1"]:
            self.assertFalse(u.maven_stable(v), v)
        for v in ["2.11.0", "2026.09.00", "4.13.2", "5.5.1"]:
            self.assertTrue(u.maven_stable(v), v)

    def test_gradle_same_line_allows_calendar_versions(self):
        self.assertTrue(u.gradle_compatible("2026.09.00", "2027.01.00"))
        self.assertTrue(u.gradle_compatible("2.11.0", "2.12.1"))
        self.assertFalse(u.gradle_compatible("2.11.0", "3.0.0"))
        self.assertFalse(u.gradle_compatible("2.11.0", "2.10.0"))


class AgeFilter(unittest.TestCase):
    rels = [
        u.Release("1.2.0", d("2026-10-01T00:00:00")),
        u.Release("1.3.0", d("2026-11-05T00:00:00")),
        u.Release("1.3.1", d("2026-11-13T00:00:00")),
        u.Release("1.4.0", d("2026-11-10T00:00:00"), yanked=True),
        u.Release("2.0.0", d("2026-11-01T00:00:00")),
        u.Release("1.5.0-rc.1", d("2026-11-01T00:00:00")),
    ]

    def test_newest_old_enough_in_line(self):
        r = u.pick_newest(self.rels, CUTOFF, line="1.2.0")
        self.assertEqual(r.version, "1.3.0")

    def test_skips_yanked_prerelease_and_too_new(self):
        r = u.pick_newest(self.rels, CUTOFF, line="1.2.0", upto="1.3.1")
        self.assertEqual(r.version, "1.3.0")

    def test_no_cutoff_takes_newest(self):
        self.assertEqual(u.pick_newest(self.rels, None, line="1.2.0").version, "1.3.1")

    def test_any_line(self):
        self.assertEqual(u.pick_newest(self.rels, CUTOFF).version, "2.0.0")

    def test_floor(self):
        self.assertIsNone(u.pick_newest(self.rels, CUTOFF, line="1.2.0", above="1.3.0"))


class Docker(unittest.TestCase):
    text = (
        "# c\n"
        "FROM node:26-alpine@sha256:" + "a" * 64 + " AS web\n"
        "RUN x\n"
        "FROM rust:1-alpine@sha256:" + "b" * 64 + " AS build\n"
        "FROM build AS test\n"
        "FROM scratch\n"
    )

    def test_parse_froms(self):
        self.assertEqual(
            u.parse_froms(self.text),
            [("node", "26-alpine", "sha256:" + "a" * 64), ("rust", "1-alpine", "sha256:" + "b" * 64)],
        )

    def test_repin_keeps_stage_name(self):
        out = u.repin(self.text, "node", "26-alpine", "28-alpine", "sha256:" + "c" * 64)
        self.assertIn("FROM node:28-alpine@sha256:" + "c" * 64 + " AS web\n", out)
        self.assertIn("FROM rust:1-alpine@sha256:" + "b" * 64, out)

    def test_node_next_lts_only(self):
        sched = {
            "v26": {"lts": "2026-10-28", "end": "2029-04-30"},
            "v27": {"start": "2026-10-20", "end": "2027-06-01"},
            "v28": {"lts": "2027-10-26", "end": "2030-04-30"},
        }
        self.assertIsNone(u.node_lts_target(sched, 26, d("2026-11-15T00:00:00")))
        self.assertEqual(u.node_lts_target(sched, 26, d("2027-10-30T00:00:00")), 28)
        self.assertIsNone(u.node_lts_target(sched, 26, d("2027-10-27T00:00:00")))
        self.assertEqual(u.node_lts_target({"v24": {"lts": "2025-10-28", "end": "2028-04-30"}, **sched}, 24, d("2026-11-15T00:00:00")), 26)

    def test_temurin_lts(self):
        self.assertEqual(u.temurin_lts_target([8, 11, 17, 21, 25], 21), 25)
        self.assertIsNone(u.temurin_lts_target([8, 11, 17, 21, 25], 25))

    def test_major_tag(self):
        self.assertEqual(u.major_tag("26-alpine"), (26, "{}-alpine"))
        self.assertEqual(u.major_tag("25-jdk"), (25, "{}-jdk"))
        self.assertIsNone(u.major_tag("alpine"))


class Actions(unittest.TestCase):
    def test_parse_uses(self):
        text = "      - uses: actions/checkout@" + "1" * 40 + " # v7.0.1\n      - uses: ./local\n      - uses: docker/build-push-action@" + "2" * 40 + " # v7.4.0\n"
        self.assertEqual(
            u.parse_uses(text),
            [("actions/checkout", "1" * 40, "v7.0.1"), ("docker/build-push-action", "2" * 40, "v7.4.0")],
        )

    def test_same_major_release(self):
        rels = [
            u.Release("v7.2.0", d("2026-11-10T00:00:00")),
            u.Release("v7.1.0", d("2026-10-01T00:00:00")),
            u.Release("v8.0.0", d("2026-09-01T00:00:00")),
        ]
        self.assertEqual(u.pick_action(rels, "v7.0.1", d("2026-11-08T00:00:00")).version, "v7.1.0")
        self.assertEqual(u.pick_action(rels, "v7.0.1", d("2026-11-14T00:00:00")).version, "v7.2.0")
        self.assertIsNone(u.pick_action(rels, "v7.2.0", d("2026-11-14T00:00:00")))

    def test_repin_uses(self):
        text = "uses: a/b@" + "1" * 40 + " # v1.0.0\nuses: a/b@" + "1" * 40 + " # v1.0.0\n"
        out = u.repin_uses(text, "a/b", "1" * 40, "3" * 40, "v1.2.0")
        self.assertEqual(out.count("a/b@" + "3" * 40 + " # v1.2.0"), 2)


class Cargo(unittest.TestCase):
    lock = (
        'version = 4\n\n[[package]]\nname = "a"\nversion = "1.0.0"\nsource = "registry+https://github.com/rust-lang/crates.io-index"\n\n'
        '[[package]]\nname = "b"\nversion = "0.3.1"\nsource = "registry+https://github.com/rust-lang/crates.io-index"\n\n'
        '[[package]]\nname = "b"\nversion = "0.4.0"\nsource = "registry+https://github.com/rust-lang/crates.io-index"\n\n'
        '[[package]]\nname = "wg-chores"\nversion = "2.0.3"\n'
    )

    def test_lock_packages_registry_only(self):
        self.assertEqual(u.cargo_lock_packages(self.lock), [("a", "1.0.0"), ("b", "0.3.1"), ("b", "0.4.0")])

    def test_lock_changes_pairs_by_compat_line(self):
        old = [("a", "1.0.0"), ("b", "0.3.1"), ("b", "0.4.0")]
        new = [("a", "1.2.0"), ("b", "0.3.1"), ("b", "0.4.2"), ("c", "1.0.0")]
        self.assertEqual(
            u.lock_changes(old, new),
            [("a", "1.0.0", "1.2.0"), ("b", "0.4.0", "0.4.2"), ("c", None, "1.0.0")],
        )

    def test_bump_req_keeps_precision(self):
        self.assertEqual(u.bump_req("0.8", "0.9.2"), "0.9")
        self.assertEqual(u.bump_req("1", "2.0.1"), "2")
        self.assertEqual(u.bump_req("0.6.0", "0.7.1"), "0.7.1")
        self.assertEqual(u.bump_req("4.1", "5.0.3"), "5.0")

    def test_set_req_all_sections(self):
        text = (
            '[dependencies]\ntokio = { version = "1", features = ["rt"] }\nrpassword = "7"\nwg = { path = "." }\n\n'
            '[dev-dependencies]\ntokio = { version = "1", features = ["test-util"] }\n'
        )
        out = u.cargo_set_req(text, "tokio", "2")
        self.assertEqual(out.count('tokio = { version = "2"'), 2)
        self.assertIn('rpassword = "7"', u.cargo_set_req(text, "rpassword", "7"))
        self.assertIn('rpassword = "8"', u.cargo_set_req(text, "rpassword", "8"))

    def test_manifest_reqs(self):
        text = (
            '[package]\nname = "x"\nversion = "1.0.0"\n[dependencies]\naxum = { version = "0.8", default-features = false }\nurl = "2"\n'
            'local = { path = "." }\n[dev-dependencies]\ntower = { version = "0.5" }\n'
        )
        self.assertEqual(u.cargo_manifest_reqs(text), {"axum": "0.8", "url": "2", "tower": "0.5"})


class Npm(unittest.TestCase):
    def test_lock_versions_by_name(self):
        lock = {"packages": {"": {}, "node_modules/a": {"version": "1.0.0"}, "node_modules/x/node_modules/a": {"version": "2.0.0"},
                             "node_modules/@s/b": {"version": "3.0.0"}, "node_modules/c": {"version": "1.0.0", "link": True}}}
        self.assertEqual(u.npm_lock_versions(lock), {"a": {"1.0.0", "2.0.0"}, "@s/b": {"3.0.0"}})

    def test_changes_ignore_moves(self):
        old = {"a": {"1.0.0"}, "fsevents": {"2.3.2", "2.3.3"}, "b": {"1.0.0"}}
        new = {"a": {"1.1.0"}, "fsevents": {"2.3.3", "2.3.2"}, "b": {"1.0.0"}, "c": {"1.0.0"}}
        self.assertEqual(u.npm_changes(old, new), {"a": ["1.1.0"], "c": ["1.0.0"]})


class Toolchain(unittest.TestCase):
    def test_rust_stable(self):
        text = 'manifest-version = "2"\ndate = "2026-11-12"\n[pkg.rust]\nversion = "1.101.0 (abc 2026-11-10)"\n'
        self.assertEqual(u.rust_stable(text), ("1.101.0", d("2026-11-12T00:00:00")))

    def test_set_channel(self):
        text = '[toolchain]\nchannel = "1.100.0"\nprofile = "minimal"\n'
        self.assertIn('channel = "1.101.0"', u.set_channel(text, "1.101.0"))


class Gradle(unittest.TestCase):
    toml = (
        '[versions]\nagp = "9.5.1"\nroom = "2.8.5"\n\n[libraries]\n'
        'room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }\n'
        'room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }\n'
        'bom = { group = "androidx.compose", name = "compose-bom", version = "1.0.0" }\n'
        '[plugins]\nroom = { id = "androidx.room", version.ref = "room" }\nandroid-application = { id = "com.android.application", version.ref = "agp" }\n'
    )

    def test_refs(self):
        refs = u.gradle_refs(self.toml)
        self.assertEqual(refs["room"], [("androidx.room", "room-runtime"), ("androidx.room", "room-ktx"), ("androidx.room", "androidx.room.gradle.plugin")])
        self.assertEqual(refs["agp"], [("com.android.application", "com.android.application.gradle.plugin")])

    def test_set_version(self):
        out = u.gradle_set_version(self.toml, "room", "2.9.0")
        self.assertIn('room = "2.9.0"', out)
        self.assertIn('agp = "9.5.1"', out)

    def test_maven_metadata(self):
        xml = "<metadata><versioning><versions><version>1.0</version><version>1.1-rc01</version><version>1.1</version></versions></versioning></metadata>"
        self.assertEqual(u.maven_versions(xml), ["1.0", "1.1-rc01", "1.1"])


class Ignore(unittest.TestCase):
    def test_parse_and_match(self):
        ign = u.parse_ignore("# c\nnpm @playwright/test all fastpi's Chromium must match\nnpm typescript major svelte-check needs 5 or 6\n")
        self.assertTrue(ign.ignored("npm", "@playwright/test", "compatible"))
        self.assertTrue(ign.ignored("npm", "typescript", "major"))
        self.assertFalse(ign.ignored("npm", "typescript", "compatible"))
        self.assertFalse(ign.ignored("cargo", "typescript", "major"))

    def test_reason_required(self):
        with self.assertRaises(ValueError):
            u.parse_ignore("npm vite major\n")
        with self.assertRaises(ValueError):
            u.parse_ignore("npm vite sometimes because\n")


class Candidates(unittest.TestCase):
    def test_rotate(self):
        items = ["a", "b", "c", "d"]
        self.assertEqual(u.rotate(items, 0, 2), ["a", "b"])
        self.assertEqual(u.rotate(items, 3, 2), ["d", "a"])
        self.assertEqual(u.rotate(items, 5, 9), ["b", "c", "d", "a"])
        self.assertEqual(u.rotate([], 5, 2), [])

    def test_paths_allowed(self):
        self.assertTrue(u.path_allowed("server/Cargo.lock"))
        self.assertTrue(u.path_allowed("android/build-env/Dockerfile"))
        self.assertFalse(u.path_allowed(".github/workflows/ci.yml"))
        self.assertFalse(u.path_allowed("server/src/main.rs"))

    def test_flags(self):
        self.assertEqual(u.change_flags(["server/Cargo.lock"]), {"android": False, "image": True})
        self.assertEqual(u.change_flags(["android/app/gradle.lockfile"]), {"android": True, "image": False})
        self.assertEqual(u.change_flags(["rust-toolchain.toml"]), {"android": False, "image": False})

    def test_pick_landing(self):
        self.assertEqual(u.pick_landing("m", "b", "pass", [("s1", "fail"), ("s2", "pass"), ("s3", "pass")]), ("s2", "b"))
        self.assertEqual(u.pick_landing("m", "b", "pass", [("s1", "fail")]), ("b", "b"))
        self.assertEqual(u.pick_landing("m", "b", "fail", [("s1", "pass")]), (None, None))
        self.assertEqual(u.pick_landing("m", "m", None, [("s1", "pass")]), ("s1", "m"))
        self.assertEqual(u.pick_landing("m", "m", None, []), (None, "m"))


class FakeGitHub:
    def __init__(self, results, main_status=200):
        self.repo = "o/r"
        self.results = results
        self.main_status = main_status
        self.dispatched, self.deleted, self.main = [], [], None

    def api(self, method, path, data=None):
        return [{"ref": "refs/heads/auto-update-9"}] if "matching-refs" in path else {}

    def set_branch(self, name, sha):
        pass

    def delete_branch(self, name):
        self.deleted.append(name)

    def commit(self, branch, head, title, files):
        return f"sha-{branch}"

    def dispatch(self, workflow, ref, inputs):
        self.dispatched.append((workflow, ref, inputs))

    def find_run(self, workflow, branch, sha):
        return branch

    def run_result(self, run_id):
        return self.results[run_id], f"https://example.com/{run_id}"

    def update_main(self, sha):
        self.main = sha
        return self.main_status


class Ship(unittest.TestCase):
    def setUp(self):
        import json
        import tempfile
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        files = {"server/Cargo.lock": "eA=="}
        (self.dir / "base.json").write_text(json.dumps({"title": "deps", "files": files}))
        (self.dir / "separate-1.json").write_text(json.dumps({"title": "a major", "files": {"web/package.json": "eA=="}}))
        (self.dir / "separate-2.json").write_text(json.dumps({"title": "toolchain", "files": {"rust-toolchain.toml": "eA=="}}))

    def tearDown(self):
        self.tmp.cleanup()

    def args(self, weekly=False):
        import argparse
        return argparse.Namespace(candidates=str(self.dir), main_sha="m", timeout=1, release=True, weekly_rebuild=weekly)

    def test_lands_first_passing_separate_on_top_of_base(self):
        gh = FakeGitHub({"auto-update": "success", "auto-update-1": "failure", "auto-update-2": "success"})
        self.assertEqual(u.ship(self.args(), gh), 0)
        self.assertEqual(gh.main, "sha-auto-update-2")
        self.assertIn(("ci.yml", "auto-update", {"skip_android": "true"}), gh.dispatched)
        self.assertIn(("release.yml", "main", {"no_cache": "false"}), gh.dispatched)
        self.assertIn("auto-update-9", gh.deleted)
        self.assertNotIn("auto-update-1", gh.deleted[1:])

    def test_red_base_lands_nothing_but_weekly_rebuild_runs(self):
        gh = FakeGitHub({"auto-update": "failure", "auto-update-1": "success", "auto-update-2": "success"})
        self.assertEqual(u.ship(self.args(weekly=True), gh), 1)
        self.assertIsNone(gh.main)
        self.assertIn(("release.yml", "main", {"no_cache": "true"}), gh.dispatched)

    def test_main_moved_lands_nothing_and_starts_no_release(self):
        gh = FakeGitHub({"auto-update": "success", "auto-update-1": "failure", "auto-update-2": "failure"}, main_status=422)
        self.assertEqual(u.ship(self.args(), gh), 0)
        self.assertFalse([d for d in gh.dispatched if d[0] == "release.yml"])

    def test_rejects_files_outside_the_allowed_set(self):
        import json
        (self.dir / "separate-3.json").write_text(json.dumps({"title": "evil", "files": {".github/workflows/ci.yml": "eA=="}}))
        with self.assertRaises(RuntimeError):
            u.ship(self.args(), FakeGitHub({}))


if __name__ == "__main__":
    unittest.main()
