# Mixed sync analysis

Scenario: `mixed-20260924-01`.

Records: 60. Starts: 10. Minimum valid starts: 10. Command and player coverage: complete. Acoustic status: not_measured.

| Measurement | Clock source | Valid | Failed | Median ms | p95 ms | Max ms | Target p95 ms | Max uncertainty ms |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| command_issued | coordinator_estimate | 10 | 0 | 33.50 | 111.00 | 111.00 | 75.00 | 47.00 |
| playback_observed | coordinator_estimate | 10 | 0 | 511.50 | 586.00 | 586.00 | 559.00 | 47.00 |
| acoustic_onset | external_capture | 0 | 0 | n/a | n/a | n/a | n/a | n/a |

Command timing records when each client issued its playback command. Player timing records when each client observed its player running. Only external capture records measure audible onset.

This report does not evaluate the Phase 0 acoustic gate.
