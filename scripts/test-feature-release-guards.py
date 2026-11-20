import importlib.util,unittest,io,tempfile,pathlib
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('release',pathlib.Path(__file__).with_name('release-feature-1.14.28.py'));release=importlib.util.module_from_spec(spec);spec.loader.exec_module(release)
class ReleaseGuards(unittest.TestCase):
 def test_policy_preserves_secrets_and_integrations_but_excludes_only_reviewed_metadata(self):
  base={'DB_PASSWORD':'fixture-secret','AMAZON_WRITE_ENABLED':'true','APP_BUILD_VERSION':'1.14.27','APP_ADMIN_PASSWORD':'bootstrap-fixture'};same=dict(base,APP_BUILD_VERSION='1.14.28',APP_ADMIN_PASSWORD='other',**release.FLAGS);self.assertEqual(release.fingerprint(base),release.fingerprint(same))
  for key,value in [('DB_PASSWORD','different'),('AMAZON_WRITE_ENABLED','false')]:self.assertNotEqual(release.fingerprint(base),release.fingerprint(dict(base,**{key:value})))
 def test_launcher_changes_only_build_and_reviewed_new_flags(self):
  original=b'#!/bin/bash\nexport AMAZON_WRITE_ENABLED=true\nexport DB_PASSWORD=fixture\nexport APP_BUILD_VERSION=1.14.27\nexec java -jar app.jar\n';new=release.new_start(original);expected=original.replace(b'APP_BUILD_VERSION=1.14.27',b'APP_BUILD_VERSION=1.14.28').replace(b'exec java',(''.join('export '+k+'='+v+'\n' for k,v in release.FLAGS.items())).encode()+b'exec java');self.assertEqual(expected,new)
  with self.assertRaises(AssertionError):release.new_start(original+b'exec other\n')
  with self.assertRaises(AssertionError):release.new_start(original+b'export APP_FBA_REPLENISHMENT_ENABLED=false\n')
 def test_insufficient_disk_refuses_before_service_commands(self):
  with patch.object(release,'free',return_value=100),patch.object(release,'run') as command:
   with self.assertRaises(RuntimeError):release.require_space(101)
   command.assert_not_called()
 def test_backup_cap_never_calls_restore_or_service_commands(self):
  class Proc:
   stdout=io.BytesIO(b'12345');returncode=0
   def terminate(self):pass
   def wait(self,**kw):return 0
  with tempfile.TemporaryDirectory() as d,patch.object(release,'CAP',4),patch.object(release.subprocess,'Popen',return_value=Proc()),patch.object(release,'run') as command:
   with self.assertRaises(RuntimeError):release.bounded_backup(pathlib.Path(d)/'dump',pathlib.Path(d)/'log')
   command.assert_not_called()
if __name__=='__main__':unittest.main()
