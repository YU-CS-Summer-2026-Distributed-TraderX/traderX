"""Shared adapter profiles and recoverable execution signal, including CLI processes."""
PROFILE = {'adapter': 'transport-mock-v1', 'calculations': ['transport-check'],
           'marketInputs': 'NOT_SUPPLIED', 'usableForRisk': False}


HTTP_PROFILE = {**PROFILE, 'adapter': 'http-transport-mock-draft-1'}


class Deferred(RuntimeError):
    """Remote execution may still exist; keep its durable attempt for reconciliation."""

W0_PROFILE = {'adapter':'alex-w0-local-v1', 'engineCommit':'cb9b277a9de702b2ba4f0bcda431a396f54c029a',
              'calculations':['npv','accruedInterest','rateSensitivity','rateGamma','theta','vega','varEs'],
              'marketInputs':'NOT_SUPPLIED', 'usableForRisk':False}

# Operator opt-in: separate state directory and compatibility identity; never a W0 pin bump.
PRICING_PROFILE = {'adapter':'alex-pricing-local-provisional-v1',
                   'engineCommit':'e7246e1765a2f9b4d4dd6049c97d1baa66319be7',
                   'assumedProfileId':'flat-3pct-v1',
                   'inputScope':'original-2025-06-02-bill-note-v2-terms-v1',
                   'calculations':['npv','accruedInterest','noteParallelBump'],
                   'marketInputs':'EXPLICIT_ASSUMED_PROFILE', 'usableForRisk':False}
