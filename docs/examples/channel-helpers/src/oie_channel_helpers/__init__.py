"""Small, dependency-free example; not a healthcare data model or validator."""

from dataclasses import asdict, dataclass
from typing import Mapping


@dataclass(frozen=True)
class Patient:
    patient_id: str
    name: str

    @classmethod
    def from_mapping(cls, value: Mapping):
        patient_id = value["patient_id"]
        name = value["name"]
        if not isinstance(patient_id, str) or not isinstance(name, str):
            raise ValueError("patient_id and name must be strings")
        return cls(patient_id.strip(), name.strip())

    def as_dict(self):
        return asdict(self)
