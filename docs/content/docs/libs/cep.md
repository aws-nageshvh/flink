---
title: Event Processing (CEP)
weight: 1
type: docs
aliases:
  - /dev/libs/cep.html
  - /apis/streaming/libs/cep.html
---
<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# FlinkCEP - Complex event processing for Flink

FlinkCEP is the Complex Event Processing (CEP) library implemented on top of Flink.
It allows you to detect event patterns in an endless stream of events, giving you the opportunity to get hold of what's important in your
data.

This page describes the API calls available in Flink CEP. We start by presenting the [Pattern API](#the-pattern-api),
which allows you to specify the patterns that you want to detect in your stream, before presenting how you can
[detect and act upon matching event sequences](#detecting-patterns). We then present the assumptions the CEP
library makes when [dealing with lateness](#handling-lateness-in-event-time) in event time and how you can
[migrate your job](#migrating-from-an-older-flink-versionpre-13) from an older Flink version to Flink-1.13.

## Getting Started

If you want to jump right in, [set up a Flink program]({{< ref "docs/dev/configuration/overview" >}}) and
add the FlinkCEP dependency to the `pom.xml` of your project.

{{< tabs "3b3e4675-dd86-4b2d-8487-9c8711f234b3" >}}
{{< tab "Java" >}}
{{< artifact flink-cep >}}
{{< /tab >}}
{{< /tabs >}}

FlinkCEP is not part of the binary distribution. See how to link with it for cluster execution 
[here]({{< ref "docs/dev/configuration/overview" >}}).

Now you can start writing your first CEP program using the Pattern API.

{{< hint warning >}}
The events in the `DataStream` to which
you want to apply pattern matching must implement proper `equals()` and `hashCode()` methods
because FlinkCEP uses them for comparing and matching events.
{{< /hint >}}

```java
DataStream<Event> input = ...;

Pattern<Event, ?> pattern = Pattern.<Event>begin("start")
    .where(SimpleCondition.of(event -> event.getId() == 42))
    .next("middle")
    .subtype(SubEvent.class)
    .where(SimpleCondition.of(subEvent -> subEvent.getVolume() >= 10.0))
    .followedBy("end")
    .where(SimpleCondition.of(event -> event.getName().equals("end")));

PatternStream<Event> patternStream = CEP.pattern(input, pattern);

DataStream<Alert> result = patternStream.process(
    new PatternProcessFunction<Event, Alert>() {
        @Override
        public void processMatch(
                Map<String, List<Event>> pattern,
                Context ctx,
                Collector<Alert> out) throws Exception {
            out.collect(createAlertFrom(pattern));
        }
    });
```

## The Pattern API

The pattern API allows you to define complex pattern sequences that you want to extract from your input stream.

Each complex pattern sequence consists of multiple simple patterns, i.e. patterns looking for individual events with the same properties. From now on, we will call these simple patterns **patterns**, and the final complex pattern sequence we are searching for in the stream, the **pattern sequence**. You can see a pattern sequence as a graph of such patterns, where transitions from one pattern to the next occur based on user-specified
*conditions*, e.g. `event.getName().equals("end")`. A **match** is a sequence of input events which visits all
patterns of the complex pattern graph, through a sequence of valid pattern transitions.

{{< hint info >}}
Each pattern must have a unique name, which you use later to identify the matched events.
{{< /hint >}}

{{< hint danger >}}
Pattern names **CANNOT** contain the character `":"`.
{{< /hint >}}

In the rest of this section we will first describe how to define [Individual Patterns](#individual-patterns), and then how you can combine individual patterns into [Complex Patterns](#combining-patterns).

### Individual Patterns

A **Pattern** can be either a *singleton* or a *looping* pattern. Singleton patterns accept a single
event, while looping patterns can accept more than one. In pattern matching symbols, the pattern `"a b+ c? d"` (or `"a"`, followed by *one or more* `"b"`'s, optionally followed by a `"c"`, followed by a `"d"`), `a`, `c?`, and `d` are
singleton patterns, while `b+` is a looping one. By default, a pattern is a singleton pattern and you can transform
it to a looping one by using [Quantifiers](#quantifiers). Each pattern can have one or more
[Conditions](#conditions) based on which it accepts events.

#### Quantifiers

In FlinkCEP, you can specify looping patterns using these methods: `pattern.oneOrMore()`, for patterns that expect one or more occurrences of a given event (e.g. the `b+` mentioned before); and `pattern.times(#ofTimes)`, for patterns that
expect a specific number of occurrences of a given type of event, e.g. 4 `a`'s; and `pattern.times(#fromTimes, #toTimes)`, for patterns that expect a specific minimum number of occurrences and a maximum number of occurrences of a given type of event, e.g. 2-4 `a`s.

You can make looping patterns greedy using the `pattern.greedy()` method, but you cannot yet make group patterns greedy. You can make all patterns, looping or not, optional using the `pattern.optional()` method.

For a pattern named `start`, the following are valid quantifiers:

```java
// expecting 4 occurrences
start.times(4);

// expecting 0 or 4 occurrences
start.times(4).optional();

// expecting 2, 3 or 4 occurrences
start.times(2, 4);

// expecting 2, 3 or 4 occurrences and repeating as many as possible
start.times(2, 4).greedy();

// expecting 0, 2, 3 or 4 occurrences
start.times(2, 4).optional();

// expecting 0, 2, 3 or 4 occurrences and repeating as many as possible
start.times(2, 4).optional().greedy();

// expecting 1 or more occurrences
start.oneOrMore();

// expecting 1 or more occurrences and repeating as many as possible
start.oneOrMore().greedy();

// expecting 0 or more occurrences
start.oneOrMore().optional();

// expecting 0 or more occurrences and repeating as many as possible
start.oneOrMore().optional().greedy();

// expecting 2 or more occurrences
start.timesOrMore(2);

// expecting 2 or more occurrences and repeating as many as possible
start.timesOrMore(2).greedy();

// expecting 0, 2 or more occurrences
start.timesOrMore(2).optional()

// expecting 0, 2 or more occurrences and repeating as many as possible
start.timesOrMore(2).optional().greedy();
```

#### Conditions

For every pattern you can specify a condition that an incoming event has to meet in order to be "accepted" into the pattern e.g. its value should be larger than 5,
or larger than the average value of the previously accepted events.
You can specify conditions on the event properties via the `pattern.where()`, `pattern.or()` or `pattern.until()` methods.
These can be either `IterativeCondition`s or `SimpleCondition`s.

**Iterative Conditions:** This is the most general type of condition. This is how you can specify a condition that
accepts subsequent events based on properties of the previously accepted events or a statistic over a subset of them.

Below is the code for an iterative condition that accepts the next event for a pattern named "middle" if its name starts
with "foo", and if the sum of the prices of the previously accepted events for that pattern plus the price of the current event do not exceed the value of 5.0. Iterative conditions can be powerful, especially in combination with looping patterns, e.g. `oneOrMore()`.

```java
middle.oneOrMore()
    .subtype(SubEvent.class)
    .where(new IterativeCondition<SubEvent>() {
        @Override
        public boolean filter(SubEvent value, Context<SubEvent> ctx) throws Exception {
            if (!value.getName().startsWith("foo")) {
                return false;
            }
    
            double sum = value.getPrice();
            for (Event event : ctx.getEventsForPattern("middle")) {
                sum += event.getPrice();
            }
            return Double.compare(sum, 5.0) < 0;
        }
    });
```

{{< hint info >}}
The call to `ctx.getEventsForPattern(...)` finds all the
previously accepted events for a given potential match. The cost of this operation can vary, so when implementing
your condition, try to minimize its use.
{{< /hint >}}

Described context gives one access to event time characteristics as well. For more info see [Time context](#time-context).

**Simple Conditions:** This type of condition extends the aforementioned `IterativeCondition` class and decides
whether to accept an event or not, based *only* on properties of the event itself.

```java
start.where(SimpleCondition.of(value -> value.getName().startsWith("foo")));
```

Finally, you can also restrict the type of the accepted event to a subtype of the initial event type (here `Event`)
via the `pattern.subtype(subClass)` method.

```java
start.subtype(SubEvent.class)
        .where(SimpleCondition.of(value -> ... /*some condition*/));
```

**Combining Conditions:** As shown above, you can combine the `subtype` condition with additional conditions. This holds for every condition. You can arbitrarily combine conditions by sequentially calling `where()`. The final result will be the logical **AND** of the results of the individual conditions. To combine conditions using **OR**, you can use the `or()` method, as shown below.

```java
pattern.where(SimpleCondition.of(value -> ... /*some condition*/))
        .or(SimpleCondition.of(value -> ... /*some condition*/));
```

**Stop condition:** In case of looping patterns (`oneOrMore()` and `oneOrMore().optional()`) you can
also specify a stop condition, e.g. accept events with value larger than 5 until the sum of values is smaller than 50.

To better understand it, have a look at the following example. Given

* pattern like `"(a+ until b)"` (one or more `"a"` until `"b"`)

* a sequence of incoming events `"a1" "c" "a2" "b" "a3"`

* the library will output results: `{a1 a2} {a1} {a2} {a3}`.

As you can see `{a1 a2 a3}` or `{a2 a3}` are not returned due to the stop condition.

#### `where(condition)`

Defines a condition for the current pattern. To match the pattern, an event must satisfy the condition.
Multiple consecutive where() clauses lead to their conditions being `AND`ed.

```java
pattern.where(new IterativeCondition<Event>() {
    @Override
    public boolean filter(Event value, Context ctx) throws Exception {
        return ...; // some condition
    }
});
```

#### `or(condition)`

Adds a new condition which is `OR`ed with an existing one. An event can match the pattern only if it passes at least one of the conditions.

```java
pattern.where(new IterativeCondition<Event>() {
    @Override
    public boolean filter(Event value, Context ctx) throws Exception {
        return ...; // some condition
    }
}).or(new IterativeCondition<Event>() {
    @Override
    public boolean filter(Event value, Context ctx) throws Exception {
        return ...; // alternative condition
    }
});
```

#### `until(condition)`

Specifies a stop condition for a looping pattern. Meaning if event matching the given condition occurs, no more
events will be accepted into the pattern. Applicable only in conjunction with `oneOrMore()`
`NOTE:` It allows for cleaning state for corresponding pattern on event-based condition.

```java
pattern.oneOrMore().until(new IterativeCondition<Event>() {
    @Override
    public boolean filter(Event value, Context ctx) throws Exception {
        return ...; // alternative condition
    }
});
```

#### `subtype(subClass)`

Defines a subtype condition for the current pattern. An event can only match the pattern if it is of this subtype.

```java
pattern.subtype(SubEvent.class);
```

#### `oneOrMore()`

Specifies that this pattern expects at least one occurrence of a matching event.
By default a relaxed internal contiguity (between subsequent events) is used. For more info on
internal contiguity see <a href="#consecutive_java">consecutive</a>.
It is advised to use either `until()` or `within()` to enable state clearing.

```java
pattern.oneOrMore();
```

#### `timesOrMore(#times)`

Specifies that this pattern expects at least `#times` occurrences of a matching event.
By default a relaxed internal contiguity (between subsequent events) is used. For more info on
internal contiguity see <a href="#consecutive_java">consecutive</a>.

```java
pattern.timesOrMore(2);
```

#### `times(#ofTimes)`

Specifies that this pattern expects an exact number of occurrences of a matching event.
By default a relaxed internal contiguity (between subsequent events) is used.
For more info on internal contiguity see <a href="#consecutive_java">consecutive</a>.

```java
pattern.times(2);
```

#### `times(#fromTimes, #toTimes)`

Specifies that this pattern expects occurrences between `#fromTimes`
and `#toTimes` of a matching event.
By default a relaxed internal contiguity (between subsequent events) is used. For more info on
internal contiguity see <a href="#consecutive_java">consecutive</a>.

```java
pattern.times(2, 4);
```

#### `optional()`

Specifies that this pattern is optional, i.e. it may not occur at all. This is applicable to all aforementioned quantifiers.

```java
pattern.oneOrMore().optional();
```

#### `greedy()`

Specifies that this pattern is greedy, i.e. it will repeat as many as possible. This is only applicable
to quantifiers and it does not support group pattern currently.

```java
pattern.oneOrMore().greedy();
```

### Combining Patterns

Now that you've seen what an individual pattern can look like, it is time to see how to combine them
into a full pattern sequence.

A pattern sequence has to start with an initial pattern, as shown below:

```java
Pattern<Event, ?> start = Pattern.<Event>begin("start");
```

Next, you can append more patterns to your pattern sequence by specifying the desired *contiguity conditions* between
them. FlinkCEP supports the following forms of contiguity between events:

 1. **Strict Contiguity**: Expects all matching events to appear strictly one after the other, without any non-matching events in-between.

 2. **Relaxed Contiguity**: Ignores non-matching events appearing in-between the matching ones.

 3. **Non-Deterministic Relaxed Contiguity**: Further relaxes contiguity, allowing additional matches
 that ignore some matching events. 
 
To apply them between consecutive patterns, you can use:

1. `next()`, for *strict*,
2. `followedBy()`, for *relaxed*, and
3. `followedByAny()`, for *non-deterministic relaxed* contiguity.

or

1. `notNext()`, if you do not want an event type to directly follow another
2. `notFollowedBy()`, if you do not want an event type to be anywhere between two other event types.

{{< hint warning >}}
A pattern sequence cannot end with `notFollowedBy()` if the time interval is not defined via `withIn()`.
{{< /hint >}}

{{< hint warning >}}
A **NOT** pattern cannot be preceded by an optional one.
{{< /hint >}}

```java

// strict contiguity
Pattern<Event, ?> strict = start.next("middle").where(...);

// relaxed contiguity
Pattern<Event, ?> relaxed = start.followedBy("middle").where(...);

// non-deterministic relaxed contiguity
Pattern<Event, ?> nonDetermin = start.followedByAny("middle").where(...);

// NOT pattern with strict contiguity
Pattern<Event, ?> strictNot = start.notNext("not").where(...);

// NOT pattern with relaxed contiguity
Pattern<Event, ?> relaxedNot = start.notFollowedBy("not").where(...);

```

Relaxed contiguity means that only the first succeeding matching event will be matched, while
with non-deterministic relaxed contiguity, multiple matches will be emitted for the same beginning. As an example,
a pattern `"a b"`, given the event sequence `"a", "c", "b1", "b2"`, will give the following results:

1. Strict Contiguity between `"a"` and `"b"`: `{}` (no match), the `"c"` after `"a"` causes `"a"` to be discarded.

2. Relaxed Contiguity between `"a"` and `"b"`: `{a b1}`, as relaxed continuity is viewed as "skip non-matching events
till the next matching one".

3. Non-Deterministic Relaxed Contiguity between `"a"` and `"b"`: `{a b1}`, `{a b2}`, as this is the most general form.

It's also possible to define a temporal constraint for the pattern to be valid.
For example, you can define that a pattern should occur within 10 seconds via the `pattern.within()` method.
Temporal patterns are supported for both [processing and event time]({{< ref "docs/concepts/time" >}}).

{{< hint info >}}
A pattern sequence can only have one temporal constraint. If multiple such constraints are defined on different individual patterns, then the smallest is applied.
{{< /hint >}}

```java
next.within(Duration.ofSeconds(10));
```

Notice that a pattern sequence can end with `notFollowedBy()` with temporal constraint
E.g. a pattern like:

```java
Pattern.<Event>begin("start")
    .next("middle")
    .where(SimpleCondition.of(value -> value.getName().equals("a")))
    .notFollowedBy("end")
    .where(SimpleCondition.of(value -> value.getName().equals("b")))
    .within(Duration.ofSeconds(10));
```

#### Contiguity within looping patterns

You can apply the same contiguity condition as discussed in the previous [section](#combining-patterns) within a looping pattern.
The contiguity will be applied between elements accepted into such a pattern.
To illustrate the above with an example, a pattern sequence `"a b+ c"` (`"a"` followed by any(non-deterministic relaxed) sequence of one or more `"b"`'s followed by a `"c"`) with
input `"a", "b1", "d1", "b2", "d2", "b3" "c"` will have the following results:

 1. **Strict Contiguity**: `{a b1 c}`, `{a b2 c}`, `{a b3 c}` - there are no adjacent `"b"`s.

 2. **Relaxed Contiguity**: `{a b1 c}`, `{a b1 b2 c}`, `{a b1 b2 b3 c}`, `{a b2 c}`, `{a b2 b3 c}`, `{a b3 c}` - `"d"`'s are ignored.

 3. **Non-Deterministic Relaxed Contiguity**: `{a b1 c}`, `{a b1 b2 c}`, `{a b1 b3 c}`, `{a b1 b2 b3 c}`, `{a b2 c}`, `{a b2 b3 c}`, `{a b3 c}` -
    notice the `{a b1 b3 c}`, which is the result of relaxing contiguity between `"b"`'s.

For looping patterns (e.g. `oneOrMore()` and `times()`) the default is *relaxed contiguity*. If you want
strict contiguity, you have to explicitly specify it by using the `consecutive()` call, and if you want
*non-deterministic relaxed contiguity* you can use the `allowCombinations()` call.

#### `consecutive()`

Works in conjunction with `oneOrMore()` and `times()` and imposes strict contiguity between the matching
events, i.e. any non-matching element breaks the match (as in `next()`).
If not applied a relaxed contiguity (as in `followedBy()`) is used.

E.g. a pattern like:

```java
Pattern.<Event>begin("start")
    .where(SimpleCondition.of(value -> value.getName().equals("c")))
    .followedBy("middle")
    .where(SimpleCondition.of(value -> value.getName().equals("a")))
    .oneOrMore()
    .consecutive()
    .followedBy("end1")
    .where(SimpleCondition.of(value -> value.getName().equals("b")));
```

Will generate the following matches for an input sequence: `C D A1 A2 A3 D A4 B`
with consecutive applied: `{C A1 B}`, `{C A1 A2 B}`, `{C A1 A2 A3 B}`
without consecutive applied: `{C A1 B}`, `{C A1 A2 B}`, `{C A1 A2 A3 B}`, `{C A1 A2 A3 A4 B}`.

#### `allowCombinations()`

Works in conjunction with `oneOrMore()` and `times()` and imposes non-deterministic relaxed contiguity
between the matching events (as in `followedByAny()`).
If not applied a relaxed contiguity (as in `followedBy()`) is used.

E.g. a pattern like:

```java
Pattern.<Event>begin("start")
    .where(SimpleCondition.of(value -> value.getName().equals("c")))
    .followedBy("middle")
    .where(SimpleCondition.of(value -> value.getName().equals("a")))
    .oneOrMore()
    .allowCombinations()
    .followedBy("end1")
    .where(SimpleCondition.of(value -> value.getName().equals("b")));
```

Will generate the following matches for an input sequence: `C D A1 A2 A3 D A4 B`.
with combinations enabled: `{C A1 B}`, `{C A1 A2 B}`, `{C A1 A3 B}`, `{C A1 A4 B}`, `{C A1 A2 A3 B}`, `{C A1 A2 A4 B}`, `{C A1 A3 A4 B}`, `{C A1 A2 A3 A4 B}`
without combinations enabled: `{C A1 B}`, `{C A1 A2 B}`, `{C A1 A2 A3 B}`, `{C A1 A2 A3 A4 B}`.

### Groups of patterns

It's also possible to define a pattern sequence as the condition for `begin`, `followedBy`, `followedByAny` and
`next`. The pattern sequence will be considered as the matching condition logically and a `GroupPattern` will be
returned and it is possible to apply `oneOrMore()`, `times(#ofTimes)`, `times(#fromTimes, #toTimes)`, `optional()`,
`consecutive()`, `allowCombinations()` to the `GroupPattern`.

```java

Pattern<Event, ?> start = Pattern.begin(
    Pattern.<Event>begin("start").where(...).followedBy("start_middle").where(...)
);

// strict contiguity
Pattern<Event, ?> strict = start.next(
    Pattern.<Event>begin("next_start").where(...).followedBy("next_middle").where(...)
).times(3);

// relaxed contiguity
Pattern<Event, ?> relaxed = start.followedBy(
    Pattern.<Event>begin("followedby_start").where(...).followedBy("followedby_middle").where(...)
).oneOrMore();

// non-deterministic relaxed contiguity
Pattern<Event, ?> nonDetermin = start.followedByAny(
    Pattern.<Event>begin("followedbyany_start").where(...).followedBy("followedbyany_middle").where(...)
).optional();

```

#### `begin(#name)`

Defines a starting pattern.

```java
Pattern<Event, ?> start = Pattern.<Event>begin("start");
```

#### `begin(#pattern_sequence)`

Defines a starting pattern

```java
Pattern<Event, ?> start = Pattern.<Event>begin(
    Pattern.<Event>begin("start").where(...).followedBy("middle").where(...)
);
```

#### `next(#name)`

Appends a new pattern. A matching event has to directly succeed the previous matching event (strict contiguity).

```java
Pattern<Event, ?> next = start.next("middle");
```

#### `next(#pattern_sequence)`

Appends a new pattern. A sequence of matching events have to directly succeed the previous matching event (strict contiguity).

```java
Pattern<Event, ?> next = start.next(
    Pattern.<Event>begin("start").where(...).followedBy("middle").where(...)
);
```

#### `followedBy(#name)`

Appends a new pattern. Other events can occur between a matching event and the previous matching event (relaxed contiguity).

```java
Pattern<Event, ?> followedBy = start.followedBy("middle");
```

#### `followedBy(#pattern_sequence)`

Appends a new pattern. Other events can occur between a matching event and the previous matching event (relaxed contiguity).

```java
Pattern<Event, ?> followedBy = start.followedBy(
    Pattern.<Event>begin("start").where(...).followedBy("middle").where(...)
);
```

#### `followedByAny(#name)`

Appends a new pattern. Other events can occur between a matching event and the previous
matching event, and alternative matches will be presented for every alternative matching event
(non-deterministic relaxed contiguity).

```java
Pattern<Event, ?> followedByAny = start.followedByAny("middle");
```

#### `followedByAny(#pattern_sequence)`

Appends a new pattern. Other events can occur between a matching event and the previous
matching event, and alternative matches will be presented for every alternative matching event
(non-deterministic relaxed contiguity).

```java
Pattern<Event, ?> next = start.next(
    Pattern.<Event>begin("start").where(...).followedBy("middle").where(...)
);
```

#### `notNext()`

Appends a new negative pattern.
A matching (negative) event has to directly succeed the previous matching event (strict contiguity) for the partial match to be discarded.

```java
Pattern<Event, ?> notNext = start.notNext("not");
```

#### `notFollowedBy()`

Appends a new negative pattern. A partial matching event sequence will be discarded even if other events occur between the matching (negative) event and the previous matching event (relaxed contiguity).

```java
Pattern<Event, ?> notFollowedBy = start.notFollowedBy("not");
```

#### `within(time)`

Defines the maximum time interval for an event sequence to match the pattern.
If a non-completed event sequence exceeds this time, it is discarded.

```java
pattern.within(Duration.ofSeconds(10));
```

### After Match Skip Strategy

For a given pattern, the same event may be assigned to multiple successful matches. To control to how many matches an event will be assigned, you need to specify the skip strategy called `AfterMatchSkipStrategy`. There are five types of skip strategies, listed as follows:

* <strong>*NO_SKIP*</strong>: Every possible match will be emitted.
* <strong>*SKIP_TO_NEXT*</strong>: Discards every partial match that started with the same event, emitted match was started.
* <strong>*SKIP_PAST_LAST_EVENT*</strong>: Discards every partial match that started after the match started but before it ended.
* <strong>*SKIP_TO_FIRST*</strong>: Discards every partial match that started after the match started but before the first event of *PatternName* occurred.
* <strong>*SKIP_TO_LAST*</strong>: Discards every partial match that started after the match started but before the last event of *PatternName* occurred.

Notice that when using *SKIP_TO_FIRST* and *SKIP_TO_LAST* skip strategy, a valid *PatternName* should also be specified.

For example, for a given pattern `b+ c` and a data stream `b1 b2 b3 c`, the differences between these four skip strategies are as follows:

<table class="table table-bordered">
    <tr>
        <th class="text-left" style="width: 25%">Skip Strategy</th>
        <th class="text-center" style="width: 25%">Result</th>
        <th class="text-center"> Description</th>
    </tr>
    <tr>
        <td><strong>NO_SKIP</strong></td>
        <td>
            <code>b1 b2 b3 c</code><br>
            <code>b2 b3 c</code><br>
            <code>b3 c</code><br>
        </td>
        <td>After found matching <code>b1 b2 b3 c</code>, the match process will not discard any result.</td>
    </tr>
    <tr>
        <td><strong>SKIP_TO_NEXT</strong></td>
        <td>
            <code>b1 b2 b3 c</code><br>
            <code>b2 b3 c</code><br>
            <code>b3 c</code><br>
        </td>
        <td>After found matching <code>b1 b2 b3 c</code>, the match process will not discard any result, because no other match could start at b1.</td>
    </tr>
    <tr>
        <td><strong>SKIP_PAST_LAST_EVENT</strong></td>
        <td>
            <code>b1 b2 b3 c</code><br>
        </td>
        <td>After found matching <code>b1 b2 b3 c</code>, the match process will discard all started partial matches.</td>
    </tr>
    <tr>
        <td><strong>SKIP_TO_FIRST</strong>[<code>b</code>]</td>
        <td>
            <code>b1 b2 b3 c</code><br>
            <code>b2 b3 c</code><br>
            <code>b3 c</code><br>
        </td>
        <td>After found matching <code>b1 b2 b3 c</code>, the match process will try to discard all partial matches started before <code>b1</code>, but there are no such matches. Therefore nothing will be discarded.</td>
    </tr>
    <tr>
        <td><strong>SKIP_TO_LAST</strong>[<code>b</code>]</td>
        <td>
            <code>b1 b2 b3 c</code><br>
            <code>b3 c</code><br>
        </td>
        <td>After found matching <code>b1 b2 b3 c</code>, the match process will try to discard all partial matches started before <code>b3</code>. There is one such match <code>b2 b3 c</code></td>
    </tr>
</table>

Have a look also at another example to better see the difference between NO_SKIP and SKIP_TO_FIRST:
Pattern: `(a | b | c) (b | c) c+.greedy d` and sequence: `a b c1 c2 c3 d` Then the results will be:


<table class="table table-bordered">
    <tr>
        <th class="text-left" style="width: 25%">Skip Strategy</th>
        <th class="text-center" style="width: 25%">Result</th>
        <th class="text-center"> Description</th>
    </tr>
    <tr>
        <td><strong>NO_SKIP</strong></td>
        <td>
            <code>a b c1 c2 c3 d</code><br>
            <code>b c1 c2 c3 d</code><br>
            <code>c1 c2 c3 d</code><br>
        </td>
        <td>After found matching <code>a b c1 c2 c3 d</code>, the match process will not discard any result.</td>
    </tr>
    <tr>
        <td><strong>SKIP_TO_FIRST</strong>[<code>c*</code>]</td>
        <td>
            <code>a b c1 c2 c3 d</code><br>
            <code>c1 c2 c3 d</code><br>
        </td>
        <td>After found matching <code>a b c1 c2 c3 d</code>, the match process will discard all partial matches started before <code>c1</code>. There is one such match <code>b c1 c2 c3 d</code>.</td>
    </tr>
</table>

To better understand the difference between NO_SKIP and SKIP_TO_NEXT take a look at following example:
Pattern: `a b+` and sequence: `a b1 b2 b3` Then the results will be:


<table class="table table-bordered">
    <tr>
        <th class="text-left" style="width: 25%">Skip Strategy</th>
        <th class="text-center" style="width: 25%">Result</th>
        <th class="text-center"> Description</th>
    </tr>
    <tr>
        <td><strong>NO_SKIP</strong></td>
        <td>
            <code>a b1</code><br>
            <code>a b1 b2</code><br>
            <code>a b1 b2 b3</code><br>
        </td>
        <td>After found matching <code>a b1</code>, the match process will not discard any result.</td>
    </tr>
    <tr>
        <td><strong>SKIP_TO_NEXT</strong></td>
        <td>
            <code>a b1</code><br>
        </td>
        <td>After found matching <code>a b1</code>, the match process will discard all partial matches started at <code>a</code>. This means neither <code>a b1 b2</code> nor <code>a b1 b2 b3</code> could be generated.</td>
    </tr>
</table>

To specify which skip strategy to use, just create an `AfterMatchSkipStrategy` by calling:
<table class="table table-bordered">
    <tr>
        <th class="text-left" width="25%">Function</th>
        <th class="text-center">Description</th>
    </tr>
    <tr>
        <td><code>AfterMatchSkipStrategy.noSkip()</code></td>
        <td>Create a <strong>NO_SKIP</strong> skip strategy </td>
    </tr>
    <tr>
        <td><code>AfterMatchSkipStrategy.skipToNext()</code></td>
        <td>Create a <strong>SKIP_TO_NEXT</strong> skip strategy </td>
    </tr>
    <tr>
        <td><code>AfterMatchSkipStrategy.skipPastLastEvent()</code></td>
        <td>Create a <strong>SKIP_PAST_LAST_EVENT</strong> skip strategy </td>
    </tr>
    <tr>
        <td><code>AfterMatchSkipStrategy.skipToFirst(patternName)</code></td>
        <td>Create a <strong>SKIP_TO_FIRST</strong> skip strategy with the referenced pattern name <i>patternName</i></td>
    </tr>
    <tr>
        <td><code>AfterMatchSkipStrategy.skipToLast(patternName)</code></td>
        <td>Create a <strong>SKIP_TO_LAST</strong> skip strategy with the referenced pattern name <i>patternName</i></td>
    </tr>
</table>

Then apply the skip strategy to a pattern by calling:

```java
AfterMatchSkipStrategy skipStrategy = ...;
Pattern.begin("patternName", skipStrategy);
```

{{< hint info >}}
For `SKIP_TO_FIRST`/`LAST` there are two options how to handle cases when there are no events mapped to the PatternName. By default a NO_SKIP strategy will be used in this case. The other option is to throw exception in such situation.
One can enable this option by:
{{< /hint >}}

```java
AfterMatchSkipStrategy.skipToFirst(patternName).throwExceptionOnMiss();
```

## Detecting Patterns

After specifying the pattern sequence you are looking for, it is time to apply it to your input stream to detect
potential matches. To run a stream of events against your pattern sequence, you have to create a `PatternStream`.
Given an input stream `input`, a pattern `pattern` and an optional comparator `comparator` used to sort events with the same timestamp in case of EventTime or that arrived at the same moment, you create the `PatternStream` by calling:

```java
DataStream<Event> input = ...;
Pattern<Event, ?> pattern = ...;
EventComparator<Event> comparator = ...; // optional

PatternStream<Event> patternStream = CEP.pattern(input, pattern, comparator);
```

The input stream can be *keyed* or *non-keyed* depending on your use-case.

{{< hint info >}}
Applying your pattern on a non-keyed stream will result in a job with parallelism equal to 1.
{{< /hint >}}

### Selecting from Patterns

Once you have obtained a `PatternStream` you can apply transformation to detected event sequences. The suggested way of doing that
is by `PatternProcessFunction`.

A `PatternProcessFunction` has a `processMatch` method which is called for each matching event sequence.
It receives a match in the form of `Map<String, List<IN>>` where the key is the name of each pattern in your pattern
sequence and the value is a list of all accepted events for that pattern (`IN` is the type of your input elements).
The events for a given pattern are ordered by timestamp. The reason for returning a list of accepted events for each
pattern is that when using looping patterns (e.g. `oneToMany()` and `times()`), more than one event may be accepted for a given pattern.

```java
class MyPatternProcessFunction<IN, OUT> extends PatternProcessFunction<IN, OUT> {
    @Override
    public void processMatch(Map<String, List<IN>> match, Context ctx, Collector<OUT> out) throws Exception;
        IN startEvent = match.get("start").get(0);
        IN endEvent = match.get("end").get(0);
        out.collect(OUT(startEvent, endEvent));
    }
}
```

The `PatternProcessFunction` gives access to a `Context` object. Thanks to it, one can access time related
characteristics such as `currentProcessingTime` or `timestamp` of current match (which is the timestamp of the last element assigned to the match).
For more info see [Time context](#time-context).
Through this context one can also emit results to a [side-output]({{< ref "docs/dev/datastream/side_output" >}}).


#### Handling Timed Out Partial Patterns

Whenever a pattern has a window length attached via the `within` keyword, it is possible that partial event sequences
are discarded because they exceed the window length. To act upon a timed out partial match one can use `TimedOutPartialMatchHandler` interface.
The interface is supposed to be used in a mixin style. This mean you can additionally implement this interface with your `PatternProcessFunction`.
The `TimedOutPartialMatchHandler` provides the additional `processTimedOutMatch` method which will be called for every timed out partial match.

```java
class MyPatternProcessFunction<IN, OUT> extends PatternProcessFunction<IN, OUT> implements TimedOutPartialMatchHandler<IN> {
    @Override
    public void processMatch(Map<String, List<IN>> match, Context ctx, Collector<OUT> out) throws Exception;
        ...
    }

    @Override
    public void processTimedOutMatch(Map<String, List<IN>> match, Context ctx) throws Exception;
        IN startEvent = match.get("start").get(0);
        ctx.output(outputTag, T(startEvent));
    }
}
```

<span class="label label-info">Note</span> The `processTimedOutMatch` does not give one access to the main output. You can still emit results
through [side-outputs]({{< ref "docs/dev/datastream/side_output" >}}) though, through the `Context` object.


#### Convenience API

The aforementioned `PatternProcessFunction` was introduced in Flink 1.8 and since then it is the recommended way to interact with matches.
One can still use the old style API like `select`/`flatSelect`, which internally will be translated into a `PatternProcessFunction`.

```java
PatternStream<Event> patternStream = CEP.pattern(input, pattern);

OutputTag<String> outputTag = new OutputTag<String>("side-output"){};

SingleOutputStreamOperator<ComplexEvent> flatResult = patternStream.flatSelect(
    outputTag,
    new PatternFlatTimeoutFunction<Event, TimeoutEvent>() {
        public void timeout(
                Map<String, List<Event>> pattern,
                long timeoutTimestamp,
                Collector<TimeoutEvent> out) throws Exception {
            out.collect(new TimeoutEvent());
        }
    },
    new PatternFlatSelectFunction<Event, ComplexEvent>() {
        public void flatSelect(Map<String, List<IN>> pattern, Collector<OUT> out) throws Exception {
            out.collect(new ComplexEvent());
        }
    }
);

DataStream<TimeoutEvent> timeoutFlatResult = flatResult.getSideOutput(outputTag);
```

## Time in CEP library

### Handling Lateness in Event Time

In `CEP` the order in which elements are processed matters. To guarantee that elements are processed in the correct order when working in event time, an incoming element is initially put in a buffer where elements are *sorted in ascending order based on their timestamp*, and when a watermark arrives, all the elements in this buffer with timestamps smaller than that of the watermark are processed. This implies that elements between watermarks are processed in event-time order.

{{< hint info >}}
The library assumes correctness of the watermark when working in event time.
{{< /hint >}}

To guarantee that elements across watermarks are processed in event-time order, Flink's CEP library assumes
*correctness of the watermark*, and considers as *late* elements whose timestamp is smaller than that of the last
seen watermark. Late elements are not further processed. Also, you can specify a sideOutput tag to collect the late elements come after the last seen watermark, you can use it like this.

```java
PatternStream<Event> patternStream = CEP.pattern(input, pattern);

OutputTag<String> lateDataOutputTag = new OutputTag<String>("late-data"){};

SingleOutputStreamOperator<ComplexEvent> result = patternStream
    .sideOutputLateData(lateDataOutputTag)
    .select(
        new PatternSelectFunction<Event, ComplexEvent>() {...}
    );

DataStream<String> lateData = result.getSideOutput(lateDataOutputTag);

```

### Time context

In [PatternProcessFunction](#selecting-from-patterns) as well as in [IterativeCondition](#conditions) user has access to a context
that implements `TimeContext` as follows:

```java
/**
 * Enables access to time related characteristics such as current processing time or timestamp of
 * currently processed element. Used in {@link PatternProcessFunction} and
 * {@link org.apache.flink.cep.pattern.conditions.IterativeCondition}
 */
@PublicEvolving
public interface TimeContext {

	/**
	 * Timestamp of the element currently being processed.
	 *
	 * <p>In case of {@link org.apache.flink.streaming.api.TimeCharacteristic#ProcessingTime} this
	 * will be set to the time when event entered the cep operator.
	 */
	long timestamp();

	/** Returns the current processing time. */
	long currentProcessingTime();
}
```

This context gives user access to time characteristics of processed events (incoming records in case of `IterativeCondition` and matches in case of `PatternProcessFunction`).
Call to `TimeContext#currentProcessingTime` always gives you the value of current processing time and this call should be preferred to e.g. calling `System.currentTimeMillis()`.

In case of `TimeContext#timestamp()` the returned value is equal to assigned timestamp in case of `EventTime`. In `ProcessingTime` this will equal to the point of time when said event entered
cep operator (or when the match was generated in case of `PatternProcessFunction`). This means that the value will be consistent across multiple calls to that method.

## Optional Configuration

Options to configure the cache capacity of Flink CEP `SharedBuffer`.
It could accelerate the CEP operate process speed and limit the number of elements of cache in pure memory. 

<span class="label label-info">Note</span> It's only effective to limit usage of memory when `state.backend.type` was set as `rocksdb`, which would transport the elements exceeded the number of the cache into the rocksdb state storage instead of memory state storage.
The configuration items are helpful for memory limitation when the `state.backend.type` is set as rocksdb. By contrast，when the `state.backend.type` is set as not `rocksdb`, the cache would cause performance decreased. Compared with old cache implemented with `Map`, the state part will contain more elements swapped out from new guava-cache, which would make it heavier to `copy on write` for state.

{{< generated/cep_cache_configuration >}}

## Examples

The following example detects the pattern `start, middle(name = "error") -> end(name = "critical")` on a keyed data
stream of `Events`. The events are keyed by their `id`s and a valid pattern has to occur within 10 seconds.
The whole processing is done with event time.

```java
StreamExecutionEnvironment env = ...;

DataStream<Event> input = ...;

DataStream<Event> partitionedInput = input.keyBy(new KeySelector<Event, Integer>() {
	@Override
	public Integer getKey(Event value) throws Exception {
		return value.getId();
	}
});

Pattern<Event, ?> pattern = Pattern.<Event>begin("start")
    .next("middle")
    .where(SimpleCondition.of(value -> value.getName().equals("error")))
    .followedBy("end")
    .where(SimpleCondition.of(value -> value.getName().equals("critical")))
    .within(Duration.ofSeconds(10));

PatternStream<Event> patternStream = CEP.pattern(partitionedInput, pattern);

DataStream<Alert> alerts = patternStream.select(new PatternSelectFunction<Event, Alert>() {
	@Override
	public Alert select(Map<String, List<Event>> pattern) throws Exception {
		return createAlert(pattern);
	}
});
```

## Migrating from an older Flink version(pre 1.5)

### Migrating from Flink <= 1.5

In Flink 1.13 we dropped direct savepoint backward compatibility with Flink <= 1.5. If you want to restore
from a savepoint taken from an older version, migrate it first to a newer version (1.6-1.12), take a savepoint
and then use that savepoint to restore with Flink >= 1.13.


{{< top >}}
