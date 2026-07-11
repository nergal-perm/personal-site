---
title: "Software Archaeology"
contentType: "Essay"
topic: "Software craft"
publishDate: "May 1, 2024"
updatedDate: "May 12, 2024"
readingTime: "7 min read"
status: "Published"
location: "Essays / Software craft"
wordCount: "1,350"
revision: 2
tags: ["state machines", "decision tables", "DBSD", "modeling", "control states"]
abstract: >
  Before we dive into the specifics of the Decision-Based Software Development
  (DBSD) methodology, it helps to understand a few underlying concepts. They were
  developed decades ago and have since faded from mainstream enterprise practice —
  which is why this part of the series is called “Software archaeology.”
figure:
  label: "FIG. 1"
  title: "From Computational to Control States"
  caption: "A computational state becomes a control state through conditions; commands then drive the transitions between control states."
  steps:
    - { n: 1, label: "Computational", icon: "grid" }
    - { n: 2, label: "Condition", icon: "filter" }
    - { n: 3, label: "Control", icon: "node" }
    - { n: 4, label: "Command", icon: "bolt" }
    - { n: 5, label: "Transition", icon: "swap" }
sources:
  - { n: 1, text: "Dijkstra, E. Co-operating sequential processes. 1968." }
  - { n: 2, text: "Humby, E. Programs from decision tables. 1973." }
  - { n: 3, text: "My notes on Decision-Based Software Development (2023–2024)." }
related:
  center: "DBSD"
  nodes:
    - "Finite State Machines"
    - "Decision Tables"
    - "Control States"
    - "Command Pattern"
    - "State Transitions"
backlinks:
  - { title: "Decision-Based Software Development", sub: "Series / Part 00" }
  - { title: "Control vs. Computational States", sub: "Note" }
  - { title: "Decision Tables in Practice", sub: "Field note" }
  - { title: "FSMs for Business Logic", sub: "Model note" }
---

## Software as a set of states

First of all, let's answer a question: What's the purpose behind writing any software?

The answer is simple: we create software to empower users to solve their problems and alleviate
their frustrations. In essence, we provide them with the tools to shape their world into a state
that meets their needs.

Take, for instance, an application that allows a user to order pizza. The end goal is to have a hot,
delicious pizza delivered to the user's door — that is the final state, "pizza delivered".
However, before the pizza can be delivered, several other states must be achieved:

- pizza must be prepared
- payment must be processed
- delivery address must be specified
- order must be placed

In essence, to reach our end goal, our system and its entities, which model real-world objects, must
navigate through a series of predefined states. Without this progression, it would be impossible to
solve the user's problem and achieve the desired state.

Well, there's a huge caveat here. The delivery address is definitely part of the program state,
right? But what if there are infinite delivery addresses (and there really are)? It means that the
number of possible states is infinite as well. How can we possibly account for all of them?

## States are not created equal

Let's call all the possible states of the system _computational states_. They are potentially
infinite, but only differ quantitatively from each other. They don't carry significant business
meaning and directly determine only the results of actions, but not the actions themselves.

In our pizza delivery scenario, the specific delivery address, while seemingly important, is
actually a computational state. It's a variable that can take on an infinite number of values, but
doesn't fundamentally change the business logic of the system. The system doesn't care about the
specifics of the address. The courier will deliver the pizza to any valid address within the
area of service.

The specific address doesn't carry significant business meaning in the context of state transitions
in the system. It's the transition from 'address unspecified' to 'valid address specified' that
truly matters. That's because those two states are _control states_. They are few, have important
business meaning, and qualitatively differ from each other. They determine the set of
possible actions that the entity takes.

> Therefore, the software should focus on managing the control states, as these are the
> states that truly matter in the context of the business logic of the system. The computational
> states, while potentially infinite and varying quantitatively, do not fundamentally alter the
> operation of the system.

The idea of such a separation is not new. Edsger Dijkstra proposed "deriving" programs from a
predefined finite number of important states back in the late 1960s [^1].

## Control states are always "conditional"

Let's consider a delivery address as a computational state in a pizza delivery application.
This computational state can be transformed into a control state by applying one or several
_conditions_ to it. For example, the _condition_ could be a check to see if the address falls within
the service area. If the address is within the service area, the control state could be 'valid
address specified'. If not, the control state could be 'invalid address specified'.

Obviously, for such a transition, we need to somehow change some basic computational states. This
change can be initiated by UI clients, external systems, timers, etc. It really helps to look at
those computational state changes as a set of self-contained actions, like the "Command" pattern in
object-oriented programming.

## State-Transition paradigm and finite state machines

The concept of control states and state transitions is a fundamental part of the State-Transition
paradigm. This paradigm is used in software development to model the behavior of systems that
can be in a finite number of states and transition between them based on certain conditions.

Finite state machines (FSMs) are a common implementation of the State-Transition paradigm. FSMs are
a mathematical model of computation that consists of a finite number of states, transitions between
those states, and actions that are performed when a transition occurs. They provide a clear and
concise way to manage states and transitions, which makes them a natural fit for the
Decision-Based Software Development paradigm.

## Decision Tables

The calculation of conditions on a given computational state is a pivotal component of _decision
tables_, a well-established and refined technique. Decision tables provide a structured and compact
representation of applied conditions. They offer several benefits:

1. **Simplicity** — they present logic in a structured, easy-to-understand format, which helps when
   communicating with non-technical stakeholders.
2. **Completeness** — they help ensure that all possible combinations of conditions have been
   considered.
3. **Maintainability** — they separate the business logic from the code, making the system easier to
   modify as business requirements change.

A very compact book on decision tables and their practical application to software development was
written by E. Humby in 1973 [^2]. So it, too, is part of our software archaeology journey.

## Conclusion

We've explored the concept of software as a set of states, distinguishing between computational and
control states. Control states are the key states that determine the flow of the system and the
actions that can be taken, while computational states are quantitatively infinite and do not
fundamentally alter the operation of the system.

Both techniques — state-transition modeling and decision tables — have been around for decades, hence
the term "software archaeology". Yet they remain relevant and valuable tools in modern software
development. We'll discover their practical application in the next part of the series.

[^1]: Dijkstra, E. "Co-operating sequential processes," 1968.

[^2]: Humby, E. (1973). Programs from decision tables.
