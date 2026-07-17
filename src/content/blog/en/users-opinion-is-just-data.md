---
aliases: []
claimKinds:
- normative
contentType: claim
date: '2026-07-10'
description: Мнение пользователей — один из источников данных о продукте и видении,
  а не приказ к исполнению
id: users-opinion-is-just-data
language: en
links: []
publish: true
sourceHash: 17ec8e7a5f63912fb265643b65f9b484855dcdb82966c3c04659d24eaab811ed
sourceLanguage: ru
sources:
- attestation: explicit
  confidence: high
  evidence:
  - kind: text
    value: '"We viewed their input as only one source of information about our product
      and overall vision. In fact, we were much more likely to run experiments on
      our customers than we were to cater to their whims"; the method''s formula is
      "a focus on what customers want (without asking them)."'
  link:
    label: The Lean Startup
    target: book-the-lean-startup
  locator:
  - kind: text
    value: Introduction, before Origins of the Lean Startup
- attestation: implicit_assumption
  confidence: medium
  evidence:
  - kind: text
    value: Cagan requires a product manager to attend to user requests and behavior
      while making independently reasoned decisions; "the best design is also a hypothesis,"
      tested with users through prototypes. He does not state the formula "data, not
      commands"; the connection is made here.
  link:
    label: Inspired
  locator:
  - kind: text
    value: 'Synopsis: the product manager''s role, Discovery phase'
statement: Мнение пользователей — один из источников данных о продукте и видении,
  а не приказ к исполнению
supports:
- label: A startup exists to learn how to build a sustainable business
  target: startup-goal-is-to-learn
tags: []
title: Мнение пользователей — данные, а не приказы
topics: []
translatedAt: '2026-07-18'
translationOf: users-opinion-is-just-data
translationProfile: codex-agent-v1
translationStatus: generated
---

There are three ways to treat the customer's voice: obey it ("the customer is
always right," so build to every whim), ignore it (pure visionary conviction),
or take Ries's route and listen to it as data. Gather it continuously, then test
it through experiments. At IMVU, the team spoke with early adopters every day
and, pointedly, did not simply do what they asked. They ran experiments on them.

Two mechanisms explain why a request is not a requirement:

- **Stated and revealed preferences diverge.** Words about future behavior are
  unreliable; behavior in an experiment is the reliable signal (objective
  metrics such as logins and registration speed). Hence the paradoxical formula:
  learn what customers want without asking them.
- **Data do not interpret themselves.** The selection and interpretation of
  signals are determined by mental models - the ladder of inference in
  *Adaptation to the environment depends on objective perception*. A user's
  request is their interpretation of their problem, not the problem itself.
  The product version in Cagan's *Inspired* is that a product manager solves
  the user's problem rather than executing the user's request.

The boundary matters: this claim does not permit ignoring users. Qualitative
feedback is collected alongside quantitative feedback; experiments are run
*on* and *about* users. The only thing revoked is the automatic authority of
their words as orders.

The rule also applies to input from internal stakeholders: an internal
customer's request for a feature is likewise input to an experiment designed to
falsify an assumption, not a finished specification.

### How this supports [A startup exists to learn how to build a sustainable business](/en/claims/startup-goal-is-to-learn/)

Validated learning depends on this claim: an experiment is meaningful only when
behavior weighs more than words. Otherwise, surveys alone would be sufficient
for "validated knowledge." This claim gives the method its signal source.
