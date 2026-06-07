"""A single simulated regression case. Mirror of RegressionCase.java."""
from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class RegressionCase:
    seq: int
    case_id: str
    name: str
    domain: str
    action: str
    variant: str
    http_method: str
    endpoint: str
    outcome: str           # "PASS" | "FAIL" | "SKIP"
    http_status: int
    failure_reason: Optional[str]
    skip_reason: Optional[str]
    sleep_ms: int

    def act_ms(self) -> int:
        """Duration attributed to the 'call endpoint' step (the bulk of the case)."""
        return self.sleep_ms
