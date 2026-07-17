---
aliases: []
claimKinds:
- causal
- factual
contentType: claim
date: '2026-07-10'
description: Рыночный провал инстинктивно лечат инженерными средствами — и это умножает
  провал
id: engineering-fix-to-market-failure
language: en
links: []
publish: true
sourceHash: 5c9acc952118822677baabaf654ea1c2db8b5864c43b9c0b72934d053fc83037
sourceLanguage: ru
sources:
- attestation: explicit
  confidence: high
  evidence:
  - kind: text
    value: '"I viewed these as technical problems that required technical solutions:
      better architecture, a better engineering process, better discipline, focus,
      or product vision. These supposed fixes led to still more failure." Ries includes
      product vision among the "technical" remedies, not only code.'
  link:
    label: The Lean Startup
    target: book-the-lean-startup
  locator:
  - kind: text
    value: Introduction, Origins of the Lean Startup
statement: Рыночный провал инстинктивно лечат инженерными средствами — и это умножает
  провал
supports:
- label: A startup rejects predictable-domain management, not management itself
  target: startup-management-uncertainty
tags: []
title: Рыночный провал инстинктивно лечат инженерными средствами
topics: []
translatedAt: '2026-07-18'
translationOf: engineering-fix-to-market-failure
translationProfile: codex-agent-v1
translationStatus: generated
---

When a product stalls in the market, its makers instinctively strengthen the
engineering side: improve the architecture, the development process, the
discipline, the focus, or the product vision. Ries records what happens next.
These fixes do not merely fail to cure the problem; they multiply the failure by
increasing the investment in something that has never been tested.

There is an important limit to this evidence. Ries is recounting an
autobiographical case (n=1), and he qualifies the reaction with "largely because
of my background." Generalizing from that case to a universal instinct is an
[ET] extrapolation, supported by the retrospective case below rather than proved
by Ries's story alone.

Two mechanisms, both [ET] hypotheses, operate at different stages:

- **The competence streetlight** explains the initial misdiagnosis. People look
  for the cause where they know how to make repairs. Market uncertainty lies
  outside an engineer's area of mastery; code lies inside it. Ries partly
  grounds this mechanism himself with "largely because of my background."
- **Fear of falsification (the sunk-cost fallacy)** explains why the error
  persists. A market test threatens to devalue the work already invested, so the
  test is postponed while the investment keeps growing. The loop reinforces
  itself: the more the team has invested, the more frightening it becomes to
  test the assumption, and the more the team invests. The structural antidote comes from *work in
  progress*: work that has not begun and remains outside the system is still an
  option; it has not yet become a sunk cost.

One consequence [ET] is that the most expensive part of a product is often its
riskiest and least tested decision. Cost accumulates in the same place as
unvalidated risk because a general platform is both technically attractive and
expensive, while the need for that generality is tested last. This deserves a
separate note if the idea starts doing useful work on its own.

A retrospective case [ET] makes the pattern concrete. An online-sales platform
builder spent years on platform features and proprietary decision engines while
leaving its core hypothesis untested: did the market need a builder at all? The
market rejected that hypothesis after the investment had already been made. In
retrospect, two management moves were available: test sales early on a rough
platform, or launch with manual steps outside the system as a concierge MVP.

This closes the loop back to lean. The instinct produces exactly the waste lean
is meant to remove: work that yields no validated knowledge and products nobody
wants. The antidote is simple to state and hard to practice: before allocating
resources to a new feature, run the smallest experiment that could falsify the
need for it.

### How this supports [A startup rejects predictable-domain management, not management itself](/en/claims/startup-management-uncertainty/)

It provides empirical grounding: if strong engineering remedies systematically
do not cure market failure, the cause lies outside the engineering domain. The
remedy must therefore be a discipline for uncertainty rather than further
technical intensification.
