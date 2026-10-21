# Safe Scala review

Research notes, not a deliverable for the language reference.

- `review.md`: summary and critical review of the Safe Scala initiative (safe mode, TACIT, the CAIS '26 paper), with an addendum on the type-level restrictions.
- `plan.md`: design note for strengthening safe mode (foreign classes as `Mutable` by default, an audited immutability list, parent constructor captures), with status.
- `probes/`: the probe programs the findings rest on. `Probe.scala` and `t/*` were run against the released 3.10.0 compiler with scala-cli; `t/P`, `t/X*` and `inherited-param-captures.scala` against `main`.

The fixes that came out of this are in retronym/scala3#65.
