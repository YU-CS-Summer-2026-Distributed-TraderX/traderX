import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('provision',Path(__file__).with_name('provision-secrets.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class Provision(unittest.TestCase):
    def test_credentials_are_private_and_stable(self):
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp)/'private'
            module.provision(folder,Path('unused'),False)
            before=(folder/'credentials.json').read_bytes()
            module.provision(folder,Path('unused'),False)
            self.assertEqual(before,(folder/'credentials.json').read_bytes())
            self.assertEqual(folder.stat().st_mode & 0o777,0o700)
            self.assertEqual((folder/'credentials.json').stat().st_mode & 0o777,0o600)

    def test_uncertain_hmac_is_never_retried(self):
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp)/'private';module.provision(folder,Path('unused'),False)
            module.private_write(folder/'extract-hmac.json',{'state':'creation-started'})
            with patch.object(module,'run',return_value={'metadata':{'name':'traderx'}}) as run:
                with self.assertRaisesRegex(ValueError,'uncertain HMAC'):
                    module.provision(folder,Path('unused'),True)
                self.assertEqual(run.call_count,1)

    def test_existing_hmac_requires_reconciliation(self):
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp)/'private'
            with patch.object(module,'run',side_effect=[{'metadata':{'name':'traderx'}},[{'metadata':{}}]]) as run:
                with self.assertRaisesRegex(ValueError,'existing HMAC'):
                    module.provision(folder,Path('unused'),True)
                self.assertEqual(run.call_count,2)

    def test_cli_failure_never_discloses_response(self):
        import subprocess
        result=subprocess.CompletedProcess(['kubectl'],1,'sensitive-value','sensitive-value')
        with patch.object(module.subprocess,'run',return_value=result):
            with self.assertRaises(RuntimeError) as error:module.run(['kubectl'])
        self.assertNotIn('sensitive-value',str(error.exception))


if __name__=='__main__':unittest.main()
