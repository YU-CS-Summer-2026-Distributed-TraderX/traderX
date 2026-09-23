"""Draft schema structure/instance checks; does not establish financial or cross-file validity."""
import copy
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError

ROOT = Path(__file__).parent


class DraftSchemaTests(unittest.TestCase):
    def setUp(self):
        self.validators = {}
        self.examples = {}
        for kind in ('request', 'result'):
            schema = json.loads((ROOT / f'{kind}.schema.json').read_text())
            Draft202012Validator.check_schema(schema)
            self.validators[kind] = Draft202012Validator(schema, format_checker=FormatChecker())
            self.examples[kind] = json.loads((ROOT / 'examples' / f'{kind}.json').read_text())

    def test_schemas_and_valid_examples(self):
        for kind, data in self.examples.items():
            self.validators[kind].validate(data)

    def test_invalid_request_fields(self):
        mutations = [lambda d: d.pop('marketPackage'), lambda d: d.update(valuationTime='not-a-date'),
                     lambda d: d['portfolioBundle'].update(bundleId='not-a-hash'),
                     lambda d: d.update(calculations=['npv', 'npv']),
                     lambda d: d.update(unknownField=True)]
        for mutate in mutations:
            data = copy.deepcopy(self.examples['request']); mutate(data)
            with self.assertRaises(ValidationError): self.validators['request'].validate(data)

    def test_invalid_result_fields(self):
        mutations = [lambda d: d.update(usableForRisk=True),
                     lambda d: d['inputs']['cut'].update(consensusSequence=42),
                     lambda d: d['items'][2]['npv'].update(value=0),
                     lambda d: d['items'][0]['npv'].update(value=None),
                     lambda d: d['items'][0]['sourceIdentity'].update(contractId='wrong-kind'),
                     lambda d: d['coverage']['npv'].update(ok=-1)]
        for mutate in mutations:
            data = copy.deepcopy(self.examples['result']); mutate(data)
            with self.assertRaises(ValidationError): self.validators['result'].validate(data)

    def test_available_sensitivity_requires_numeric_bump_and_method(self):
        data = copy.deepcopy(self.examples['result'])
        measure = data['items'][1]['rateSensitivity']
        measure.update(status='ok', value=-1, method='ad-first-order', reason=None)
        with self.assertRaises(ValidationError): self.validators['result'].validate(data)
        measure['bump'] = {'curveId':'synthetic','pillarDate':None,'size':0.0001,'type':'absolute-zero-rate'}
        self.validators['result'].validate(data)
        measure['method'] = 'unspecified'
        with self.assertRaises(ValidationError): self.validators['result'].validate(data)


if __name__ == '__main__':
    unittest.main()
