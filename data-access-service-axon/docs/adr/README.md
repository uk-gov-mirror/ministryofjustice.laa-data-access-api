# Architecture Decision Records

Architecture decision records (ADRs) capture significant design choices in the Axon module, the
context in which they were made, and the conditions that should cause them to be reconsidered.

| ADR | Status | Decision |
|---|---|---|
| [0001](0001-use-a-subscribing-event-router-for-application-linking.md) | Accepted | Use a stateless subscribing event router, rather than a saga, for application linking |
| [0002](0002-separate-sensitive-data-from-domain-events.md) | Accepted | Store sensitive application data as immutable versions referenced by thin events |
| [0003](0003-define-application-behaviour-after-retention-deletion.md) | Proposed | Give applications an explicit retention-deleted state after sensitive data is deleted |
| [0004](0004-use-native-axon-replay-for-get-application.md) | Proposed | Rebuild Get Application from the committed event stream through Axon's native API |

ADRs describe why a decision was made. The developer guides describe how the resulting
implementation works today.
