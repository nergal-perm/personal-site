---
aliases: []
claimKinds:
- causal
- factual
contentType: claim
date: '2026-07-10'
description: Работающая практика без общего языка и явных принципов не передаётся
  — и не улучшается
id: method-needs-language-to-transfer
language: en
links: []
publish: true
sourceHash: df9abd957343c744972c82a17f70dac52f1819f60a2e9ea8daecb9f678b8f61b
sourceLanguage: ru
sources:
- attestation: explicit
  confidence: high
  evidence:
  - kind: text
    value: '"...these methods did not make sense, yet I could see firsthand that they
      were working. I struggled to explain the practices to new employees, investors,
      and the founders of other companies. We lacked a common language for describing
      them and concrete principles for understanding them." The methods already worked,
      but could not be detached from the people who practiced them.'
  link:
    label: The Lean Startup
    target: book-the-lean-startup
  locator:
  - kind: text
    value: Introduction, Origins of the Lean Startup
- attestation: implicit_assumption
  confidence: medium
  evidence:
  - kind: text
    value: 'The DDD note names the functions of a shared language: no "translations,"
      centralized and accessible knowledge, and the ability for knowledge to survive
      turnover from one generation of developers to the next. It does not discuss
      transferring an entrepreneurial method; the connection to Ries''s claim is made
      here.'
  link:
    label: A Ubiquitous Language in Software Development
  locator:
  - kind: text
    value: '@why-ddd'
statement: Работающая практика без общего языка и явных принципов не передаётся —
  и не улучшается
supports:
- label: 'Startup success can be engineered: the right process can be learned'
  target: learnable-startup-process
tags: []
title: Работающая практика без общего языка не передаётся
topics: []
translatedAt: '2026-07-18'
translationOf: method-needs-language-to-transfer
translationProfile: codex-agent-v1
translationStatus: generated
---

IMVU's methods demonstrably worked ("I could see firsthand that they were
working"), yet they still would not travel. New employees, investors, and
founders of other companies could not make sense of Ries's explanations. His
diagnosis: they lacked a common language for describing the practices and
concrete principles for understanding them. Without that language, a working
practice remains bound to the people who embody it. Hiring cannot scale it,
investors cannot evaluate it, and other people's experience cannot improve it.

The direction of causality matters: the practice worked **before** the theory.
Language and principles did not create the method; they made it transferable
beyond its original practitioners. That distinguishes this claim from the
overstated version, "there is no practice without theory." Here, theory is a
technology of transfer, not the source of the practice's effectiveness.

Language has a second function: improving the practice itself. Ries refined his
theory "in the process of being called on to defend and explain my insights" -
in blog posts, talks, and arguments with skeptics ("That could never work!").
An explicit practice becomes available for reasoning, criticism, and refinement.
This is *thinking by putting things into words* at the scale of an entire
movement. An inability to articulate concepts and relationships is a reliable
sign that the practice has not been worked through; the pressure to articulate
them is what deepens it.

### How this supports [Startup success can be engineered: the right process can be learned](/en/claims/learnable-startup-process/)

The claim is the load-bearing premise in the transition from "learned" to
"taught" in the chain "success can be engineered through a process -> the
process can be learned -> it can be taught." Without codification, that chain
ends with the person who already knows the method.

Parallels in the vault (the connections are made here):

- *A Ubiquitous Language in Software Development* is the DDD version of the
  same claim: a shared language within a bounded context removes translations,
  centralizes knowledge, and lets it survive turnover from one generation of
  team members to the next. The
  genesis also matches: the language arises in direct conversation with short
  feedback loops, just as Ries's language was forged while defending it to
  skeptics.
- *Different languages in software development* holds that languages determine
  thought; the higher the level of a language, the closer it is to domain
  experts' mental models.
- *Characteristics of a mature process* says that a mature process produces a
  predictable result regardless of its performer and requires documentation
  intelligible to everyone: transferability without relying on a particular
  practitioner is a sign of a mature practice.
- *Team standards as infrastructure* is a modern version of the claim,
  with AI agents in place of new employees: a senior developer's tacit knowledge
  is invisible to an agent until it has been externalized into explicit
  artifacts; a standard "scales senior intuition." Related notes include
  *Knowledge priming* and *Agent readability*.
- [Following an explicit method improves the chances of success compared with acting on a myth](/en/claims/following-method-vs-following-myth/) uses the Dreyfus model: a novice can enter a practice only through explicit rules; a tacit practice gives a novice no entry point.
