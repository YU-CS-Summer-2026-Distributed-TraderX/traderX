import json
import unittest
import provision
import transition


RAW = json.dumps(dict(schema='traderx.run.v1', epoch='fresh', eventIdScheme='epoch-v1',
                      storageLineage='fresh-storage', projectionScope='fresh-scope', adoptionEvidenceSha256=None)).encode()


class DurableController:
    def __init__(self):
        self.state = None
        self.fail_after = None
        self.calls = []

    def call(self, action, payload=None):
        self.calls.append(action)
        if action.startswith('state/'):
            return dict(self.state) if self.state else None
        if action == 'prepare':
            if not self.state:
                self.state = dict(old_scope='old-scope', new_scope='fresh-scope', descriptor_hash=provision.sha(RAW), phase='PREPARED')
        else:
            self.state['phase'] = dict(freeze='FROZEN', verify='VERIFIED', select='SELECTED', activate='COMPLETE')[action]
        if self.fail_after == action:
            self.fail_after = None
            raise OSError('simulated process loss after durable server response')
        return dict(self.state)


class TransitionTests(unittest.TestCase):
    def resume(self, controller, raw=RAW):
        return transition.resume(controller, 'transition-one', 'old-scope', raw,
                                 'http://localhost:18110', 'http://localhost:18111')

    def test_every_interrupted_phase_resumes_from_durable_state(self):
        for boundary in ('prepare', 'freeze', 'verify', 'select', 'activate'):
            with self.subTest(boundary=boundary):
                server = DurableController();server.fail_after = boundary
                with self.assertRaises(OSError):
                    self.resume(server)
                self.assertIsNotNone(server.state)
                self.assertEqual('COMPLETE', self.resume(server)['phase'])
                self.assertEqual('COMPLETE', self.resume(server)['phase'])
                self.assertEqual(1, server.calls.count('freeze'))
                self.assertEqual(1, server.calls.count('verify'))
                self.assertEqual(1, server.calls.count('select'))
                self.assertEqual(1, server.calls.count('activate'))

    def test_wrong_descriptor_never_advances_an_existing_transition(self):
        server = DurableController();self.resume(server);before = list(server.calls)
        with self.assertRaisesRegex(ValueError, 'intent differs'):
            self.resume(server, RAW.replace(b'fresh-storage', b'other-storage'))
        self.assertEqual(before + ['state/transition-one'], server.calls)

    def test_unknown_phase_is_not_inferred_or_restarted(self):
        server = DurableController();self.resume(server);server.state['phase'] = 'UNKNOWN'
        with self.assertRaisesRegex(ValueError, 'unknown durable'):
            self.resume(server)
        self.assertEqual('UNKNOWN', server.state['phase'])

    def test_remote_or_credentialed_controller_urls_are_refused(self):
        for endpoint in ('https://example.com', 'http://example.com', 'http://user:secret@localhost',
                         'http://localhost/path', 'http://localhost?query=x'):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError):
                transition.Controller(endpoint, 'test-only-token')

    def test_controller_token_is_explicit(self):
        with self.assertRaises(ValueError):
            transition.Controller('http://localhost:18112', None)

    def test_transition_identifier_cannot_be_a_path(self):
        with self.assertRaises(ValueError):
            transition.resume(DurableController(), '../other', 'old-scope', RAW, 'old', 'new')


if __name__ == '__main__':
    unittest.main()
