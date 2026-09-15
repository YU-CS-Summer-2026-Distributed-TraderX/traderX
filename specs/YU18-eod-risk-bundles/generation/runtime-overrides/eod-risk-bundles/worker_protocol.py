"""Shared adapter profiles and recoverable execution signal, including CLI processes."""
PROFILE = {'adapter': 'transport-mock-v1', 'calculations': ['transport-check'],
           'marketInputs': 'NOT_SUPPLIED', 'usableForRisk': False}


HTTP_PROFILE = {**PROFILE, 'adapter': 'http-transport-mock-draft-1'}


class Deferred(RuntimeError):
    """Remote execution may still exist; keep its durable attempt for reconciliation."""
