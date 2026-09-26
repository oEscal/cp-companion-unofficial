import importlib.util
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_release import validate_ref
from validate_repository import forbidden
from verify_signer import verify


class PublicationChecksTest(unittest.TestCase):
    def test_release_tags_must_match_application_version(self):
        for tag in ('v1.0.0', 'v1.0.0-alpha.1', 'v1.0.0-beta.1', 'v1.0.0-rc.2'):
            self.assertEqual(tag, validate_ref('refs/tags/' + tag, '1.0.0'))
        for ref in ('refs/heads/master', 'refs/tags/v0.9.0-beta.1', 'refs/tags/v1.0.0-preview.1', 'refs/tags/v1.0.0-beta.0', 'refs/tags/v1.0.0-beta.1; echo unsafe'):
            with self.subTest(ref=ref), self.assertRaises(ValueError):
                validate_ref(ref, '1.0.0')

    def test_prerelease_version_name_requires_exact_tag(self):
        self.assertEqual('v1.0.0-beta.2', validate_ref('refs/tags/v1.0.0-beta.2', '1.0.0-beta.2'))
        for tag in ('v1.0.0', 'v1.0.0-beta.1', 'v1.0.0-beta.2-rc.1'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                validate_ref('refs/tags/' + tag, '1.0.0-beta.2')

    def test_publication_rejects_generated_and_sensitive_paths(self):
        for name in ('app/build/outputs/app.apk','local.properties','release.jks','.env.prod','capture.har','.kotlin/errors/example.log','PLAN.md'):
            with self.subTest(name=name): self.assertTrue(forbidden(name))
        for name in ('gradle/wrapper/gradle-wrapper.jar','local.properties.example','.env.example','app/src/main/java/Example.kt','LICENSE'):
            with self.subTest(name=name): self.assertFalse(forbidden(name))

    def test_local_sdk_file_is_not_a_source_validation_failure(self):
        # Exercise the validator against a normal developer checkout containing an SDK file.
        import tempfile
        import shutil
        module_path=Path(__file__).resolve().parents[1]/'validate_source.py'
        spec=importlib.util.spec_from_file_location('source_validator',module_path)
        source=importlib.util.module_from_spec(spec)
        spec.loader.exec_module(source)
        root=module_path.parents[1]
        with tempfile.TemporaryDirectory() as directory:
            temp=Path(directory)
            for name in ('app/src','app/build.gradle.kts','gradle/libs.versions.toml','gradle/wrapper/gradle-wrapper.properties','build.sh','.github/workflows'):
                src=root/name; dest=temp/name
                dest.parent.mkdir(parents=True,exist_ok=True)
                if src.is_dir(): shutil.copytree(src,dest)
                else: shutil.copyfile(src,dest)
            (temp/'local.properties').write_text('sdk.dir=/example/sdk\n')
            source.ROOT=temp;source.APP=temp/'app';source.RES=temp/'app/src/main/res'
            source.check_project_invariants()
            self.assertEqual([],source.ERRORS)


class SignerChecksTest(unittest.TestCase):
    def test_old_and_new_apksigner_labels(self):
        digest = "ab" * 32
        for label in ("Signer #1", "V2 Signer"):
            verify(f"Number of signers: 1\n{label}: certificate SHA-256 digest: {digest}\n", digest.upper())

    def test_wrong_or_multiple_signers_are_rejected(self):
        for report in (
            "Number of signers: 1\nSigner #1 certificate SHA-256 digest: " + "cd" * 32,
            "Number of signers: 2\nSigner #1 certificate SHA-256 digest: " + "ab" * 32,
            "Number of signers: 1\n",
        ):
            with self.subTest(report=report), self.assertRaises(ValueError):
                verify(report, "ab" * 32)

    def test_malformed_expected_fingerprint_is_rejected(self):
        with self.assertRaises(ValueError): verify("", "not-a-fingerprint")
