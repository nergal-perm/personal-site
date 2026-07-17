---
aliases: []
contentType: essay
date: '2023-05-18'
description: An attempt to derive from TDD scale-free principles of the smallest verifiable
  change, preparing a system for change, and subsequent refactoring.
id: essay-tdd-fractality
language: en
links: []
publish: true
sourceHash: d4e3fb69e77136b8871bbaa450cbb7049f54e4c9d2cb522bd609da784a0e3349
sourceLanguage: ru
tags: []
title: The Fractality of TDD
topics:
- software
- systems
- thinking
translatedAt: '2026-07-15'
translationOf: essay-tdd-fractality
translationProfile: codex-manual-v1
translationStatus: generated
---

## Introduction

My new work project keeps delighting me with a stream of insights :) This week, the revelation for
me was the idea that TDD (Test-Driven Development) is a scale-free thing, if you "play around" a
little with its fundamental principles. Let me explain.

TDD (if I understand the canon correctly) considers exclusively the process of writing code for a
single executable process; that is, it does not rise to the level of interaction among several
services or, say, frontend and backend. This is why many of its "postulates" are phrased rather
categorically and tied specifically to this scale. It seems to me that we could try to identify a
basic set of "postulates," soften their wording, and then discover that they apply at any scale.
Probably :)

## A Failing Test for the Smallest Increment of Functionality

The main rule of TDD is that a failing test must exist before any code changes begin. Such a test
lets us pin down the required behavior and formulate a hypothesis about what the interfaces that
make this behavior possible should look like. Pinning down the behavior will be useful later, when
we refactor, while the hypothesis about the interfaces matters right now, before any code has been
written, because it stimulates discussion. At the level of individual classes, these might be
questions like "Do we really need to pass this object into this method?" At the level of individual
user stories, they might be "Is this endpoint really right for us?" or "Could we link these two
entities at an earlier stage of the process?"

At higher levels, a test may take the form of a system concept or a use case, but this does not
change the essence: first, we need a recorded description of the desired outcome.

The main rule has a fine-print addition that many people overlook. It is the requirement to move in
very small steps—laughably tiny ones. This is usually justified by how easy it makes finding errors:
if some test starts "failing" after a small change, the error must be somewhere in that small
change. Finding an error in, say, three lines of code is much easier than finding one in five new
classes of 100 lines each. This is true, but it is not the whole benefit of small steps. Truly small,
atomic changes make it possible to test every assumption underlying the code, because each
assumption requires a test, and every test is a hypothesis and a discussion of that hypothesis.

I have a sense that a minimal increment can be formulated at every system level: at the code level,
it might be supporting collections instead of a single item; at the story level, allowing something
to be selected instead of using a fixed value; at the level of the system as a whole, implementing a
workflow scenario only for a basic set of entities.

Moving in small steps requires good discipline because it is a very counterintuitive practice.
Personally, various checklists help me with this. At the unit-test level, for example, the
[ZOMBIES acronym](https://t.me/evgen_voheret/19) works well; at the story level, there is
Patterns for Splitting Stories into Smaller Ones.

For the first rule, the conclusion is this: whatever we set out to do, we must first formulate a way
to verify the result while reducing the scope of the planned work to the absolute minimum. In other
words, we want to implement the smallest possible increment of functionality and know exactly how
we will verify that it has been implemented correctly. The essence of this rule does not depend on
the level under consideration.

## The Minimum Code Needed to Pass the Test

So, we have a minimal increment of functionality described as a test at any system level, whether it
is a unit test or a user-story test scenario. The second rule of TDD says that we must make this test
pass as quickly as possible (without breaking all the other tests, naturally), and that we may write
the most terrible, dirty, and unmaintainable code to do so. _Quick green excuses all sins_—but only
for a while.

This rule has its nuances too.

First, we need to write exactly as much code as is required to pass the test. We cannot write less,
because the test will not pass, but we must not write more for several reasons. First and foremost,
YAGNI (You Ain't Gonna Need It—you will not need it): we do not yet "know" the next requirements, so
there is no justification for extra code. Moreover, such additional code is not covered by any test
(it may run during the test suite, but that does not mean the correctness of its behavior is being
checked), which means it may contain errors.

Second, at this stage we need to make the simplest design/architectural decisions for _new code_. We
should not create new domain entities with their own lifecycle if a Boolean flag on an existing
class is enough to pass the test. It is also too early to bolt a database onto the project if we can
keep all objects in memory.

Third, _new code_ can be written as badly as we like, as long as it makes the test pass. At this
stage, we can shamelessly copy and paste pieces of code and use inefficient data structures and
algorithms—anything goes. The main task right now is to implement the functional requirement
expressed by the test; we will make it beautiful later (and we absolutely must!).

I emphasized "new code" for a reason. The point is that every new test formulated at the previous
stage must impose some new requirement on our existing codebase. In other words, every new test
tells us something new about the system. In her wonderful book 99 Bottles of OOP,
Sandi Metz identifies two kinds of this new information:

1. A clarification of what exactly the code should do. These are (for the most part) functional
   requirements; they dictate the content of the code, the very _new code_ we need to write.
2. A clarification of the requirements for the code's ability to change. These are architectural
   requirements; they determine the form/structure of the _old code_. In other words, they explain
   how the old code should have been written to support the simple integration of new code.

So, before writing new code, we need to satisfy the architectural requirements. Kent Beck once put
it this way: _First make the change easy, then make an easy change_. First we need to prepare the
code for the change. If this stage reveals new domain concepts that are not yet represented in the
code, we need to introduce them. If the new functionality would fit more easily into a more general
mechanism than the one currently implemented, we need to generalize the existing mechanism. And all
of this must be done strictly before writing the new code, because refactoring and implementing new
behavior at the same time is a very bad idea.

This interpretation and formulation of the rule is not tied to the scale or level of the changes at
all. It applies equally to an individual class and to the system as a whole.

In short, the second rule of TDD is this: (1) determine the simplest way to implement the new
functional requirement, (2) refactor the old code so that the chosen approach is simpler (though
not necessarily easier) to implement, and (3) write the absolute minimum of new code required to
pass the test.
