# SPDX-License-Identifier: MPL-2.0
"""OIE Python SDK.

Reader scripts receive Message; Writers receive Response and Status. Response
transformers receive Status, and every script receives UnsupportedValue.
Explicit imports remain supported, including from reusable modules:

    from oie_python import Message, Response, Status

The engine bootstraps its verified SDK with ``python -I -S`` and enables
dependency site processing after process containment. This package has no
third-party dependencies.
"""

from ._result_types import (
    ContractError,
    Message,
    OieError,
    Response,
    Status,
    UnsupportedValue,
    UnsupportedValueError,
)

__version__ = "1.0.0"
PROTOCOL_VERSION = 1
CONTRACT_VERSION = 1

__all__ = [
    "ContractError",
    "Message",
    "OieError",
    "Response",
    "Status",
    "UnsupportedValue",
    "UnsupportedValueError",
    "__version__",
    "PROTOCOL_VERSION",
    "CONTRACT_VERSION",
]
