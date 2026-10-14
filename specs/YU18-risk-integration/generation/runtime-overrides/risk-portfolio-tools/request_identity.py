"""Wire content digest for the pinned CPU transport, independent of result values.

All JSON nodes are tagged; objects are sorted; numeric values are exact decimal
strings with trailing fractional zeros removed (-0 is 0). This normalizes JSON
formatting, key order and equal int/float numbers, NOT omitted model defaults.
The service's separate Pydantic identity retains default-expanded idempotency.
"""
from decimal import Decimal
import hashlib
import json

from profiles import CONTRACT


def content_sha256(raw):
    def tagged(value):
        if value is None:
            return ["null"]
        if isinstance(value, bool):
            return ["boolean", value]
        if isinstance(value, (int, float, Decimal)):
            number = value if isinstance(value, Decimal) else Decimal(str(value))
            if not number.is_finite():
                raise ValueError("nonfinite JSON number")
            if number.is_zero():
                return ["number", "0"]
            sign, digits, exponent = number.as_tuple()
            digits = list(digits)
            while digits[-1] == 0:
                digits.pop()
                exponent += 1
            text = ("-" if sign else "") + "".join(map(str, digits)) + "e" + str(exponent)
            return ["number", text]
        if isinstance(value, str):
            return ["string", value]
        if isinstance(value, list):
            return ["array", [tagged(item) for item in value]]
        return ["object", [[key, tagged(value[key])] for key in sorted(value)]]
    document = {"kind": "portfolio", "execution_contract": CONTRACT,
                "request": tagged(json.loads(raw, parse_float=Decimal))}
    return hashlib.sha256(json.dumps(document, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()
