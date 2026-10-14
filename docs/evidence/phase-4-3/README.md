# Phase 4.3 evidence

Captured 2026-10-14 on the laptop (k3d), `make chaos` on the `evening` set at 60×, after the
`service.instance.id` fix. Red dashed lines are the chaos suite's annotations: run started, fault,
run ended (and the next scenario's start).

- `stage1-crash-dashboard.png`: a Stage 1 worker SIGKILLed at +76 s. Stage 1 lag climbs to about 500 while
  Kafka waits out the 45 s session timeout, then drains; push latency p99 rises to 44 s and comes back down.
- `stage1-crash-trace.png`: the slowest exemplar in that spike, opened in Tempo: `poll`, `accept`,
  `produce` at 0 s, then 35 s of nothing until the surviving copy's `fanout`, then `deliver` and `send`
  for each follower (189 spans, 5 services).
- `broker-crash-dashboard.png`: the only broker SIGKILLed. Pushes stop, the outbox holds the accepted
  plays, and p99 rises to about 30 s until Kafka is back and the retry loop sends them.

Summary of that run: `stage1-crash PASS pushes 6,573 worst delay 44.1 s`,
`broker-crash PASS pushes 6,088 worst delay 24.3 s`; the full suite passed 8 of 8 earlier the same day.
